#ifndef HOSHI_LOCAL_AUDIO_FD_VFS_H
#define HOSHI_LOCAL_AUDIO_FD_VFS_H

#include "database.h"
#include "sqlite3.h"

#include <sys/stat.h>

namespace hoshi::local_audio {

class FdSource {
public:
    FdSource(int fd, std::int64_t offset, std::int64_t length);
    ~FdSource();
    FdSource(const FdSource&) = delete;
    FdSource& operator=(const FdSource&) = delete;

    int read(void* destination, int count, sqlite3_int64 position) noexcept;
    void verifyUnchanged() const;
    std::int64_t length() const { return length_; }
    int readError() const { return readError_; }

    Statistics statistics;

private:
    void validateHeader();

    int fd_ = -1;
    std::int64_t offset_;
    std::int64_t length_;
    struct stat initialStat_ {};
    int readError_ = 0;
};

int openReadOnly(FdSource& source, sqlite3** connection);

} // namespace hoshi::local_audio

#endif
