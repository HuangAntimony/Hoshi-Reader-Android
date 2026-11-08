#include "fd_vfs.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <stdexcept>
#include <string>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>
#include <utility>

namespace hoshi::local_audio {
namespace {

static_assert(sizeof(off_t) >= sizeof(std::int64_t));

constexpr char vfsName[] = "hoshi_local_audio_fd";
constexpr char mainFileName[] = "hoshi-audio-db";

// SQLite opens the main file synchronously on the opening thread. The token
// only resolves during that call and is consumed by the first main-file open.
thread_local FdSource* openingSource = nullptr;

struct FdFile {
    sqlite3_file base;
    FdSource* source;
};

FdSource& sourceFor(sqlite3_file* file) {
    return *reinterpret_cast<FdFile*>(file)->source;
}

int fileClose(sqlite3_file* file) {
    // The connection owner releases the descriptor after SQLite closes.
    file->pMethods = nullptr;
    return SQLITE_OK;
}

int fileRead(sqlite3_file* file, void* destination, int count, sqlite3_int64 position) {
    return sourceFor(file).read(destination, count, position);
}

int fileSize(sqlite3_file* file, sqlite3_int64* size) {
    *size = sourceFor(file).length();
    return SQLITE_OK;
}

const sqlite3_io_methods fileMethods{
    .iVersion = 1,
    .xClose = fileClose,
    .xRead = fileRead,
    .xWrite = [](sqlite3_file*, const void*, int, sqlite3_int64) { return SQLITE_READONLY; },
    .xTruncate = [](sqlite3_file*, sqlite3_int64) { return SQLITE_READONLY; },
    .xSync = [](sqlite3_file*, int) { return SQLITE_READONLY; },
    .xFileSize = fileSize,
    .xLock = [](sqlite3_file*, int level) {
        return level <= SQLITE_LOCK_SHARED ? SQLITE_OK : SQLITE_READONLY;
    },
    .xUnlock = [](sqlite3_file*, int) { return SQLITE_OK; },
    .xCheckReservedLock = [](sqlite3_file*, int* reserved) {
        *reserved = 0;
        return SQLITE_OK;
    },
    .xFileControl = [](sqlite3_file*, int, void*) { return SQLITE_NOTFOUND; },
    .xSectorSize = [](sqlite3_file*) { return 0; },
    .xDeviceCharacteristics = [](sqlite3_file*) { return SQLITE_IOCAP_IMMUTABLE; },
    .xShmMap = nullptr,
    .xShmLock = nullptr,
    .xShmBarrier = nullptr,
    .xShmUnmap = nullptr,
    .xFetch = nullptr,
    .xUnfetch = nullptr,
};

bool isMainFile(const char* name) {
    return name && std::strcmp(name, mainFileName) == 0;
}

int vfsOpen(sqlite3_vfs*, sqlite3_filename name, sqlite3_file* file, int flags, int* outFlags) {
    file->pMethods = nullptr;
    if (!openingSource || !isMainFile(name) ||
        (flags & (SQLITE_OPEN_MAIN_DB | SQLITE_OPEN_READONLY)) !=
            (SQLITE_OPEN_MAIN_DB | SQLITE_OPEN_READONLY) ||
        (flags & (SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_DELETEONCLOSE))) {
        return SQLITE_CANTOPEN;
    }
    reinterpret_cast<FdFile*>(file)->source = std::exchange(openingSource, nullptr);
    file->pMethods = &fileMethods;
    if (outFlags) *outFlags = SQLITE_OPEN_READONLY;
    return SQLITE_OK;
}

int vfsPathname(sqlite3_vfs*, const char* name, int count, char* out) {
    if (!isMainFile(name) || count < static_cast<int>(sizeof(mainFileName))) {
        return SQLITE_CANTOPEN;
    }
    std::memcpy(out, mainFileName, sizeof(mainFileName));
    return SQLITE_OK;
}

int vfsRandomness(sqlite3_vfs*, int count, char* out) {
    arc4random_buf(out, count);
    return count;
}

int vfsSleep(sqlite3_vfs*, int microseconds) {
    timespec remaining{microseconds / 1000000, (microseconds % 1000000) * 1000L};
    while (nanosleep(&remaining, &remaining) == -1 && errno == EINTR) {}
    return microseconds;
}

int vfsTime(sqlite3_vfs*, sqlite3_int64* milliseconds) {
    constexpr sqlite3_int64 julianUnixEpochMilliseconds = 210866760000000;
    timeval now{};
    if (gettimeofday(&now, nullptr) != 0) return SQLITE_ERROR;
    *milliseconds = julianUnixEpochMilliseconds + now.tv_sec * sqlite3_int64{1000} +
                    now.tv_usec / 1000;
    return SQLITE_OK;
}

sqlite3_vfs fdVfs{
    .iVersion = 2,
    .szOsFile = sizeof(FdFile),
    // The pager reserves eight extra characters for a journal suffix.
    .mxPathname = sizeof(mainFileName) + 8,
    .pNext = nullptr,
    .zName = vfsName,
    .pAppData = nullptr,
    .xOpen = vfsOpen,
    .xDelete = [](sqlite3_vfs*, const char*, int) { return SQLITE_READONLY; },
    .xAccess = [](sqlite3_vfs*, const char*, int, int* exists) {
        *exists = 0;
        return SQLITE_OK;
    },
    .xFullPathname = vfsPathname,
    .xDlOpen = nullptr,
    .xDlError = nullptr,
    .xDlSym = nullptr,
    .xDlClose = nullptr,
    .xRandomness = vfsRandomness,
    .xSleep = vfsSleep,
    .xCurrentTime = nullptr,
    .xGetLastError = nullptr,
    .xCurrentTimeInt64 = vfsTime,
    .xSetSystemCall = nullptr,
    .xGetSystemCall = nullptr,
    .xNextSystemCall = nullptr,
};

std::runtime_error descriptorError(const char* operation, int error) {
    return std::runtime_error(std::string(operation) + ": " + std::strerror(error));
}

struct stat descriptorStat(int fd) {
    struct stat value{};
    int rc;
    do {
        rc = fstat(fd, &value);
    } while (rc == -1 && errno == EINTR);
    if (rc == -1) throw descriptorError("Cannot inspect the local audio descriptor", errno);
    return value;
}

bool sameTime(const timespec& a, const timespec& b) {
    return a.tv_sec == b.tv_sec && a.tv_nsec == b.tv_nsec;
}

std::uint32_t bigEndian32(const unsigned char* bytes) {
    return (std::uint32_t{bytes[0]} << 24) | (std::uint32_t{bytes[1]} << 16) |
           (std::uint32_t{bytes[2]} << 8) | bytes[3];
}

} // namespace

FdSource::FdSource(int fd, std::int64_t offset, std::int64_t length)
    : offset_(offset), length_(length) {
    if (offset < 0 || length < -1) throw std::runtime_error("Invalid local audio descriptor bounds");
    do {
        fd_ = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    } while (fd_ == -1 && errno == EINTR);
    if (fd_ == -1) throw descriptorError("Cannot duplicate the local audio descriptor", errno);
    try {
        initialStat_ = descriptorStat(fd_);
        if (!S_ISREG(initialStat_.st_mode)) {
            throw std::runtime_error("Local audio requires a seekable regular file");
        }
        if (offset_ > initialStat_.st_size) {
            throw std::runtime_error("Local audio starts beyond the descriptor size");
        }
        const std::int64_t remaining = initialStat_.st_size - offset_;
        if (length_ == -1) length_ = remaining;
        if (length_ > remaining) {
            throw std::runtime_error("Local audio length exceeds the descriptor size");
        }
        validateHeader();
        verifyUnchanged();
    } catch (...) {
        close(fd_);
        throw;
    }
}

FdSource::~FdSource() {
    close(fd_);
}

void FdSource::validateHeader() {
    std::array<unsigned char, 100> header{};
    const int rc = read(header.data(), header.size(), 0);
    if (rc == SQLITE_IOERR_READ) {
        throw descriptorError("Cannot read the local audio descriptor", readError_);
    }
    if (rc != SQLITE_OK || std::memcmp(header.data(), "SQLite format 3", 16) != 0) {
        throw std::runtime_error("Local audio file does not have a complete SQLite header");
    }
    if (header[18] != 1 || header[19] != 1) {
        throw std::runtime_error("Local audio requires a static SQLite file without WAL");
    }
    const unsigned encodedPageSize = (unsigned{header[16]} << 8) | header[17];
    const unsigned pageSize = encodedPageSize == 1 ? 65536 : encodedPageSize;
    if (pageSize < 512 || (pageSize & (pageSize - 1)) != 0 || length_ % pageSize != 0) {
        throw std::runtime_error("Local audio has an invalid SQLite page size or a partial page");
    }
    // The header's page count is valid when the change counter matches the
    // version-valid-for counter. Older databases can leave both fields unset.
    if (bigEndian32(header.data() + 24) == bigEndian32(header.data() + 92) &&
        std::int64_t{bigEndian32(header.data() + 28)} * pageSize > length_) {
        throw std::runtime_error("Local audio SQLite file is truncated");
    }
}

int FdSource::read(void* destination, int count, sqlite3_int64 position) noexcept {
    readError_ = 0;
    if (position < 0 || count < 0) {
        readError_ = EINVAL;
        return SQLITE_IOERR_READ;
    }
    const auto available = std::min<sqlite3_int64>(count, std::max<sqlite3_int64>(0, length_ - position));
    auto* bytes = static_cast<unsigned char*>(destination);
    sqlite3_int64 readCount = 0;
    while (readCount < available) {
        ++statistics.readCalls;
        const ssize_t got = pread(fd_, bytes + readCount, available - readCount,
                                  offset_ + position + readCount);
        if (got == -1 && errno == EINTR) continue;
        if (got == -1) {
            readError_ = errno;
            return SQLITE_IOERR_READ;
        }
        if (got == 0) break;
        readCount += got;
        statistics.readBytes += got;
    }
    if (readCount == count) return SQLITE_OK;
    std::memset(bytes + readCount, 0, count - readCount);
    return SQLITE_IOERR_SHORT_READ;
}

void FdSource::verifyUnchanged() const {
    const auto current = descriptorStat(fd_);
#if defined(__APPLE__)
    const bool sameTimestamps = sameTime(current.st_mtimespec, initialStat_.st_mtimespec) &&
                                sameTime(current.st_ctimespec, initialStat_.st_ctimespec);
#else
    const bool sameTimestamps = sameTime(current.st_mtim, initialStat_.st_mtim) &&
                                sameTime(current.st_ctim, initialStat_.st_ctim);
#endif
    if (!sameTimestamps || current.st_size != initialStat_.st_size ||
        current.st_dev != initialStat_.st_dev || current.st_ino != initialStat_.st_ino ||
        current.st_nlink != initialStat_.st_nlink) {
        throw std::runtime_error("Local audio file changed while open");
    }
}

int openReadOnly(FdSource& source, sqlite3** connection) {
#if !defined(__ANDROID__)
    static const int registration = sqlite3_vfs_register(&fdVfs, 0);
    if (registration != SQLITE_OK) return registration;
#endif
    openingSource = &source;
    const int result = sqlite3_open_v2(mainFileName, connection, SQLITE_OPEN_READONLY | SQLITE_OPEN_PRIVATECACHE,
                                       vfsName);
    openingSource = nullptr;
    return result;
}

} // namespace hoshi::local_audio

#if defined(__ANDROID__)
// The Android amalgamation is built with SQLITE_OS_OTHER. It has no OS VFS
// capable of opening an arbitrary pathname, temporary database, or sidecar.
extern "C" int sqlite3_os_init() {
    return sqlite3_vfs_register(&hoshi::local_audio::fdVfs, 1);
}

extern "C" int sqlite3_os_end() {
    return SQLITE_OK;
}
#endif
