#include "database.h"
#include "fd_vfs.h"
#include "sqlite3.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fcntl.h>
#include <iostream>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

using hoshi::local_audio::Database;
using hoshi::local_audio::Entry;
using hoshi::local_audio::FdSource;

namespace {

void check(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}

struct Descriptor {
    int fd;
    explicit Descriptor(int value) : fd(value) { check(fd >= 0, "open fixture descriptor"); }
    ~Descriptor() { close(fd); }
    Descriptor(const Descriptor&) = delete;
    Descriptor& operator=(const Descriptor&) = delete;
};

struct FixtureDirectory {
    std::filesystem::path path;
    FixtureDirectory() {
        char pattern[] = "/tmp/hoshi-local-audio-test-XXXXXX";
        const char* directory = mkdtemp(pattern);
        check(directory, "create fixture directory");
        path = directory;
    }
    ~FixtureDirectory() { std::filesystem::remove_all(path); }
};

struct Sqlite {
    sqlite3* db = nullptr;
    Sqlite(const std::filesystem::path& path, int flags) {
        if (sqlite3_open_v2(path.c_str(), &db, flags, nullptr) != SQLITE_OK) {
            sqlite3_close(db);
            throw std::runtime_error("open reference SQLite");
        }
    }
    ~Sqlite() { sqlite3_close(db); }
    Sqlite(const Sqlite&) = delete;
    Sqlite& operator=(const Sqlite&) = delete;
    void exec(const char* sql) const {
        if (sqlite3_exec(db, sql, nullptr, nullptr, nullptr) != SQLITE_OK) {
            throw std::runtime_error(sqlite3_errmsg(db));
        }
    }
};

struct Statement {
    sqlite3_stmt* stmt = nullptr;
    Statement(const Sqlite& sqlite, const char* sql) {
        if (sqlite3_prepare_v2(sqlite.db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
            throw std::runtime_error(sqlite3_errmsg(sqlite.db));
        }
    }
    ~Statement() { sqlite3_finalize(stmt); }
};

void createFixture(const std::filesystem::path& path, const char* journal = "PRAGMA journal_mode=DELETE") {
    const Sqlite sqlite(path, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE);
    sqlite.exec(journal);
    sqlite.exec(
        "CREATE TABLE entries(source TEXT, expression TEXT, reading TEXT, file TEXT, display TEXT);"
        "CREATE TABLE android(source TEXT, file TEXT, data BLOB);"
        "CREATE INDEX entries_expression ON entries(expression);"
        "CREATE INDEX entries_reading ON entries(reading);"
        "CREATE INDEX android_key ON android(file, source);"
        "INSERT INTO entries VALUES ('辞典😀','猫𠮷','ねこ','猫.MP3',NULL);"
        "INSERT INTO entries VALUES ('辞典😀','猫','ねこ','猫.ogg','音声😀');"
        "INSERT INTO entries VALUES ('alt','猫𠮷',NULL,'alt.OPUS','');"
        "INSERT INTO entries VALUES ('excluded','他',NULL,'unsupported.aac','');"
        "INSERT INTO entries VALUES ('nul','before'||char(0)||'after',NULL,'empty.mp3','');"
        "INSERT INTO android VALUES ('辞典😀','猫.MP3',randomblob(24000));"
        "INSERT INTO android VALUES ('nul','empty.mp3',x'');"
        "INSERT INTO android VALUES ('broken','null.mp3',NULL);"
    );
}

template<class Action>
void rejects(Action action, const char* message) {
    try {
        action();
    } catch (const std::runtime_error&) {
        return;
    }
    throw std::runtime_error(message);
}

std::optional<std::vector<std::byte>> stockBlob(const Sqlite& sqlite, std::string_view source, std::string_view file) {
    Statement query(sqlite, "SELECT data FROM android WHERE source=? AND file=? LIMIT 1");
    sqlite3_bind_text(query.stmt, 1, source.data(), static_cast<int>(source.size()), SQLITE_TRANSIENT);
    sqlite3_bind_text(query.stmt, 2, file.data(), static_cast<int>(file.size()), SQLITE_TRANSIENT);
    const int result = sqlite3_step(query.stmt);
    if (result == SQLITE_DONE) return std::nullopt;
    check(result == SQLITE_ROW, "read reference audio");
    const int size = sqlite3_column_bytes(query.stmt, 0);
    if (size == 0) return std::vector<std::byte>{};
    const auto* bytes = static_cast<const std::byte*>(sqlite3_column_blob(query.stmt, 0));
    return std::vector(bytes, bytes + size);
}

void testQueries(const std::filesystem::path& fixture) {
    const Sqlite sqlite(fixture, SQLITE_OPEN_READONLY);
    const auto expectedAudio = stockBlob(sqlite, "辞典😀", "猫.MP3");
    auto database = [&] {
        Descriptor original(open(fixture.c_str(), O_RDONLY));
        check(lseek(original.fd, 7, SEEK_SET) == 7, "set caller file position");
        auto result = Database::open(original.fd, 0, -1);
        check(lseek(original.fd, 0, SEEK_CUR) == 7, "opening leaves caller offset alone");
        return result;
    }();
    check(database->sizeBytes() == static_cast<std::int64_t>(std::filesystem::file_size(fixture)), "fstat-derived length");
    unsigned hits = 0;
    database->queryEntries("猫𠮷", "ねこ", [&](const Entry& row) {
        if (row.file == "猫.MP3") {
            check(row.source == "辞典😀" && row.expression == "猫𠮷" &&
                  row.reading == "ねこ" && row.display.empty(), "Unicode fields and null display");
        } else if (row.file == "alt.OPUS") {
            check(!row.reading && row.display.empty(), "null reading stays null");
        } else {
            check(row.file == "猫.ogg" && row.display == "音声😀", "reading-only match");
        }
        ++hits;
    });
    check(hits == 3, "OR query returns row matching both predicates once");
    hits = 0;
    database->queryEntries("猫𠮷", std::nullopt, [&](const Entry&) { ++hits; });
    check(hits == 2, "expression-only query");
    database->queryEntries(std::string_view("before\0after", 12), std::nullopt, [&](const Entry& row) {
        check(row.source == "nul" && row.expression.size() == 12, "embedded NUL round trip");
        ++hits;
    });
    check(hits == 3, "embedded NUL bound to SQLite");
    std::vector<std::string> sources;
    database->querySources([&](std::string_view source) { sources.emplace_back(source); });
    std::sort(sources.begin(), sources.end());
    check(sources == std::vector<std::string>{"alt", "nul", "辞典😀"}, "distinct supported sources");
    check(database->loadAudio("辞典😀", "猫.MP3", [&](std::span<const std::byte> audio) {
        check(expectedAudio && std::ranges::equal(audio, *expectedAudio), "multi-page BLOB matches stock SQLite");
    }), "audio found");
    check(database->loadAudio("nul", "empty.mp3", [](std::span<const std::byte> audio) {
        check(audio.empty(), "empty BLOB is a real value");
    }), "empty BLOB found");
    check(!database->loadAudio("missing", "猫.MP3", [](auto) {
        throw std::runtime_error("missing row should not invoke the callback");
    }), "missing BLOB is absent");
    rejects([&] { database->loadAudio("broken", "null.mp3", [](auto) {}); }, "reject NULL audio");
    const auto before = database->statistics();
    for (int i = 0; i < 12; ++i) {
        database->queryEntries("猫𠮷", "ねこ", [](const Entry&) {});
        database->loadAudio("辞典😀", "猫.MP3", [](auto) {});
    }
    const auto after = database->statistics();
    check(before.prepareCount == 4 && after.prepareCount == 4, "four statements prepared once");
    check(after.queryCount - before.queryCount == 24, "each operation counted");
    check(before.readCalls > 0 && before.readBytes >= 24000, "read statistics include actual VFS I/O");
    check(after.readBytes == before.readBytes, "warm lookups reuse SQLite pages");
    check(!std::filesystem::exists(fixture.string() + "-journal") &&
          !std::filesystem::exists(fixture.string() + "-wal") &&
          !std::filesystem::exists(fixture.string() + "-shm"), "no SQLite sidecar files");
}

void testSlice(const std::filesystem::path& fixture, const std::filesystem::path& container) {
    const auto size = std::filesystem::file_size(fixture);
    std::vector<char> bytes(size);
    Descriptor input(open(fixture.c_str(), O_RDONLY));
    check(read(input.fd, bytes.data(), bytes.size()) == static_cast<ssize_t>(size), "read fixture bytes");
    Descriptor output(open(container.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0600));
    constexpr std::int64_t start = (std::int64_t{1} << 32) + 27;
    check(pwrite(output.fd, bytes.data(), size, start) == static_cast<ssize_t>(size), "write >4GiB sparse slice");
    check(pwrite(output.fd, "trailer", 7, start + size) == 7, "write trailing data beyond slice");
    auto database = Database::open(output.fd, start, size);
    check(database->sizeBytes() == static_cast<std::int64_t>(size), "declared slice length");
    unsigned hits = 0;
    database->queryEntries("猫𠮷", std::nullopt, [&](const Entry&) { ++hits; });
    check(hits == 2, "query bounded >4GiB sparse slice");
    rejects([&] { Database::open(output.fd, start, size - 4096); }, "reject truncated slice");
    rejects([&] { Database::open(output.fd, start, size + 4096); }, "reject slice past end");
    rejects([&] { Database::open(output.fd, -1, size); }, "reject negative offset");
    FdSource bounded(output.fd, start, size);
    std::array<char, 4> beyondEnd{'x', 'x', 'x', 'x'};
    check(bounded.read(beyondEnd.data(), beyondEnd.size(), size - 2) == SQLITE_IOERR_SHORT_READ,
          "read spanning the declared slice returns short read");
    check(beyondEnd[2] == 0 && beyondEnd[3] == 0, "short read zeroes remainder without reading trailer");
    check(ftruncate(output.fd, start + 512) == 0, "truncate live descriptor");
    check(bounded.read(beyondEnd.data(), beyondEnd.size(), 512) == SQLITE_IOERR_SHORT_READ &&
          std::ranges::all_of(beyondEnd, [](char byte) { return byte == 0; }),
          "descriptor EOF returns short read with zero fill");
    database.reset();
    check(fcntl(output.fd, F_GETFD) >= 0, "closing database does not close caller's descriptor");
}

void testInvalidSources(const FixtureDirectory& directory) {
    int pipeEnds[2];
    check(pipe(pipeEnds) == 0, "create pipe fixture");
    Descriptor readEnd(pipeEnds[0]);
    Descriptor writeEnd(pipeEnds[1]);
    rejects([&] { Database::open(readEnd.fd, 0, -1); }, "reject pipes");
    const auto invalid = directory.path / "invalid.db";
    Descriptor garbage(open(invalid.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0600));
    check(ftruncate(garbage.fd, 4096) == 0, "create malformed header");
    rejects([&] { Database::open(garbage.fd, 0, -1); }, "reject malformed SQLite header");
    check(ftruncate(garbage.fd, 10) == 0, "truncate header");
    rejects([&] { Database::open(garbage.fd, 0, -1); }, "reject short SQLite header");

    const auto wal = directory.path / "wal.db";
    createFixture(wal, "PRAGMA journal_mode=WAL");
    Descriptor walFd(open(wal.c_str(), O_RDONLY));
    rejects([&] { Database::open(walFd.fd, 0, -1); }, "reject WAL-format databases");

    const auto wrong = directory.path / "wrong.db";
    const Sqlite sqlite(wrong, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE);
    sqlite.exec("CREATE TABLE entries(source TEXT, expression TEXT);");
    Descriptor wrongFd(open(wrong.c_str(), O_RDONLY));
    rejects([&] { Database::open(wrongFd.fd, 0, -1); }, "require local-audio schema");

    const auto views = directory.path / "views.db";
    const Sqlite viewDb(views, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE);
    viewDb.exec(
        "CREATE VIEW entries AS SELECT 'source' source, 'expression' expression, "
        "'reading' reading, 'file' file, 'display' display;"
        "CREATE TABLE android(source TEXT, file TEXT, data BLOB);"
    );
    Descriptor viewsFd(open(views.c_str(), O_RDONLY));
    rejects([&] { Database::open(viewsFd.fd, 0, -1); }, "reject view in place of a local-audio table");
}

void testMutation(const std::filesystem::path& fixture) {
    Descriptor file(open(fixture.c_str(), O_RDWR));
    auto database = Database::open(file.fd, 0, -1);
    database->queryEntries("猫", std::nullopt, [](const Entry&) {});
    {
        const Sqlite writer(fixture, SQLITE_OPEN_READWRITE);
        writer.exec("UPDATE entries SET display = 'updated' WHERE expression = '猫';");
    }
    rejects([&] { database->queryEntries("猫", std::nullopt, [](const Entry&) {}); },
            "reject stat change even when pages are cached");
    database = Database::open(file.fd, 0, -1);
    check(unlink(fixture.c_str()) == 0, "unlink fixture");
    rejects([&] { database->querySources([](std::string_view) {}); },
            "reject deleted source even though the owned descriptor is still readable");
}

using Fields = std::array<std::optional<std::string>, 5>;
using Clock = std::chrono::steady_clock;

std::optional<std::string> column(sqlite3_stmt* stmt, int index) {
    if (sqlite3_column_type(stmt, index) == SQLITE_NULL) return std::nullopt;
    const auto* value = reinterpret_cast<const char*>(sqlite3_column_text(stmt, index));
    return std::string(value, sqlite3_column_bytes(stmt, index));
}

std::vector<Fields> stockRows(const Sqlite& sqlite, const std::string& term,
                              const std::optional<std::string>& reading) {
    Statement query(sqlite, reading
        ? "SELECT source,expression,reading,file,display FROM entries WHERE expression=? OR reading=?"
        : "SELECT source,expression,reading,file,display FROM entries WHERE expression=?");
    sqlite3_bind_text(query.stmt, 1, term.data(), static_cast<int>(term.size()), SQLITE_TRANSIENT);
    if (reading) {
        sqlite3_bind_text(query.stmt, 2, reading->data(), static_cast<int>(reading->size()), SQLITE_TRANSIENT);
    }
    std::vector<Fields> results;
    int step;
    while ((step = sqlite3_step(query.stmt)) == SQLITE_ROW) {
        results.push_back({column(query.stmt, 0), column(query.stmt, 1), column(query.stmt, 2),
                           column(query.stmt, 3), column(query.stmt, 4).value_or("")});
    }
    check(step == SQLITE_DONE, "finish reference entries query");
    return results;
}

double elapsedMicros(Clock::time_point start) {
    return std::chrono::duration<double, std::micro>(Clock::now() - start).count();
}

void printDistribution(const char* name, std::vector<double> samples) {
    std::sort(samples.begin(), samples.end());
    std::cout << name << " p50=" << samples[samples.size() / 2]
              << "us p95=" << samples[samples.size() * 95 / 100] << "us\n";
}

void benchmark(const std::filesystem::path& path) {
    const Sqlite stock(path, SQLITE_OPEN_READONLY);
    Statement sample(stock, "SELECT expression,reading,source,file FROM entries WHERE id>=? ORDER BY id LIMIT 8");
    std::vector<Fields> corpus;
    for (int id = 1; id <= 800001; id += 100000) {
        sqlite3_reset(sample.stmt);
        sqlite3_bind_int(sample.stmt, 1, id);
        while (sqlite3_step(sample.stmt) == SQLITE_ROW) {
            corpus.push_back({column(sample.stmt, 0), column(sample.stmt, 1),
                              column(sample.stmt, 2), column(sample.stmt, 3), std::nullopt});
        }
    }
    check(!corpus.empty(), "benchmark corpus is empty");
    Descriptor descriptor(open(path.c_str(), O_RDONLY));
    const auto started = Clock::now();
    auto database = Database::open(descriptor.fd, 0, -1);
    std::cout << "Host SQLite " << sqlite3_libversion() << ", database bytes=" << database->sizeBytes()
              << ", FD session open=" << elapsedMicros(started) << "us\n";
    std::vector<std::string> fdSources;
    database->querySources([&](std::string_view source) { fdSources.emplace_back(source); });
    Statement sources(stock, "SELECT DISTINCT source FROM entries "
                             "WHERE lower(file) LIKE '%.mp3' OR lower(file) LIKE '%.opus' OR lower(file) LIKE '%.ogg'");
    std::vector<std::string> stockSources;
    int step;
    while ((step = sqlite3_step(sources.stmt)) == SQLITE_ROW) stockSources.push_back(*column(sources.stmt, 0));
    check(step == SQLITE_DONE && stockSources == fdSources, "corpus source parity");
    std::vector<double> fdLookup, stockLookup, fdLoad, stockLoad;
    unsigned audioFound = 0;
    const auto runCorpus = [&] {
        for (const auto& row : corpus) {
            const auto& [term, reading, source, file, unused] = row;
            for (const auto& pronunciation : {std::optional<std::string>{}, reading}) {
                auto start = Clock::now();
                std::vector<Fields> actual;
                database->queryEntries(*term, pronunciation, [&](const Entry& entry) {
                    actual.push_back({std::string(entry.source), std::string(entry.expression),
                                      entry.reading ? std::optional(std::string(*entry.reading)) : std::nullopt,
                                      std::string(entry.file), std::string(entry.display)});
                });
                fdLookup.push_back(elapsedMicros(start));
                start = Clock::now();
                const auto expected = stockRows(Sqlite(path, SQLITE_OPEN_READONLY), *term, pronunciation);
                stockLookup.push_back(elapsedMicros(start));
                check(actual == expected, "ordered corpus entry parity");
            }
            auto start = Clock::now();
            std::optional<std::vector<std::byte>> actual;
            database->loadAudio(*source, *file, [&](std::span<const std::byte> bytes) {
                actual.emplace(bytes.begin(), bytes.end());
            });
            fdLoad.push_back(elapsedMicros(start));
            start = Clock::now();
            const auto expected = stockBlob(Sqlite(path, SQLITE_OPEN_READONLY), *source, *file);
            stockLoad.push_back(elapsedMicros(start));
            check(actual == expected, "corpus payload byte parity");
            if (actual) ++audioFound;
        }
    };
    runCorpus();
    fdLookup.clear();
    stockLookup.clear();
    fdLoad.clear();
    stockLoad.clear();
    audioFound = 0;
    const auto preQueries = database->statistics();
    runCorpus();
    const auto postQueries = database->statistics();
    std::cout << "Compared " << fdLookup.size() << " ordered lookup results, "
              << audioFound << "/" << corpus.size() << " audio payloads, " << fdSources.size() << " sources\n";
    std::cout << "FD lookup/payload reads=" << postQueries.readCalls - preQueries.readCalls
              << ", bytes=" << postQueries.readBytes - preQueries.readBytes
              << ", prepared statements=" << postQueries.prepareCount
              << ", new preparations=" << postQueries.prepareCount - preQueries.prepareCount << '\n';
    std::cout << "Timings from the second corpus pass (both paths warmed)\n";
    printDistribution("Retained FD lookup", fdLookup);
    printDistribution("Reopened pathname lookup", stockLookup);
    printDistribution("Retained FD payload", fdLoad);
    printDistribution("Reopened pathname payload", stockLoad);
}

} // namespace

int main(int argc, char** argv) {
    try {
        if (argc == 2) {
            benchmark(argv[1]);
            return 0;
        }
        const FixtureDirectory directory;
        const auto fixture = directory.path / "audio.db";
        createFixture(fixture);
        testQueries(fixture);
        testSlice(fixture, directory.path / "container.bin");
        testInvalidSources(directory);
        testMutation(fixture);
        std::cout << "Local audio FD fixture passed\n";
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
