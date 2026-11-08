#include "database.h"
#include "fd_vfs.h"

#include <cstring>
#include <stdexcept>
#include <string>
#include <utility>

namespace hoshi::local_audio {
namespace {

using Connection = std::unique_ptr<sqlite3, decltype(&sqlite3_close)>;
using Statement = std::unique_ptr<sqlite3_stmt, decltype(&sqlite3_finalize)>;

constexpr char entriesByExpression[] =
    "SELECT source,expression,reading,file,display FROM main.entries WHERE expression = ?";
constexpr char entriesByExpressionOrReading[] =
    "SELECT source,expression,reading,file,display FROM main.entries WHERE expression = ? OR reading = ?";
constexpr char sourcesQuery[] =
    "SELECT DISTINCT source FROM main.entries "
    "WHERE lower(file) LIKE '%.mp3' OR lower(file) LIKE '%.opus' OR lower(file) LIKE '%.ogg'";
constexpr char audioQuery[] = "SELECT data FROM main.android WHERE source = ? AND file = ? LIMIT 1";

class ResetStatement {
public:
    explicit ResetStatement(sqlite3_stmt* statement) : statement_(statement) {}
    ~ResetStatement() {
        sqlite3_reset(statement_);
        sqlite3_clear_bindings(statement_);
    }

private:
    sqlite3_stmt* statement_;
};

} // namespace

struct Database::Impl {
    FdSource source;
    Connection connection{nullptr, sqlite3_close};
    Statement byExpression{nullptr, sqlite3_finalize};
    Statement byExpressionOrReading{nullptr, sqlite3_finalize};
    Statement sources{nullptr, sqlite3_finalize};
    Statement audio{nullptr, sqlite3_finalize};

    Impl(int fd, std::int64_t offset, std::int64_t length) : source(fd, offset, length) {
        sqlite3* opened = nullptr;
        const int rc = openReadOnly(source, &opened);
        connection.reset(opened);
        requireOk(rc, "Open local audio SQLite");
        requireOk(sqlite3_db_config(opened, SQLITE_DBCONFIG_DEFENSIVE, 1, nullptr), "Configure local audio SQLite");
        requireOk(sqlite3_db_config(opened, SQLITE_DBCONFIG_TRUSTED_SCHEMA, 0, nullptr), "Configure local audio SQLite");
        requireOk(sqlite3_db_config(opened, SQLITE_DBCONFIG_ENABLE_VIEW, 0, nullptr), "Configure local audio SQLite");
        requireOk(sqlite3_exec(opened, "PRAGMA main.cache_size=-4096", nullptr, nullptr, nullptr),
                  "Set local audio SQLite cache size");
        byExpression = prepare(entriesByExpression);
        byExpressionOrReading = prepare(entriesByExpressionOrReading);
        sources = prepare(sourcesQuery);
        audio = prepare(audioQuery);
        source.verifyUnchanged();
    }

    [[noreturn]] void fail(int result, const char* operation) const {
        const char* message = connection ? sqlite3_errmsg(connection.get()) : sqlite3_errstr(result);
        std::string detail = std::string(operation) + ": " + message;
        if ((result & 0xff) == SQLITE_IOERR && source.readError() != 0) {
            detail += std::string(" (") + std::strerror(source.readError()) + ")";
        }
        throw std::runtime_error(detail);
    }

    void requireOk(int result, const char* operation) const {
        if (result != SQLITE_OK) fail(result, operation);
    }

    Statement prepare(const char* sql) {
        sqlite3_stmt* prepared = nullptr;
        requireOk(sqlite3_prepare_v3(connection.get(), sql, -1,
                                     SQLITE_PREPARE_PERSISTENT | SQLITE_PREPARE_NO_VTAB,
                                     &prepared, nullptr),
                  "Prepare local audio SQLite statement");
        ++source.statistics.prepareCount;
        return Statement(prepared, sqlite3_finalize);
    }

    void bind(sqlite3_stmt* statement, int position, std::string_view value) const {
        requireOk(sqlite3_bind_text64(statement, position, value.empty() ? "" : value.data(),
                                      value.size(), SQLITE_TRANSIENT, SQLITE_UTF8),
                  "Bind local audio SQLite query");
    }

    bool step(sqlite3_stmt* statement) const {
        const int rc = sqlite3_step(statement);
        if (rc == SQLITE_ROW) return true;
        if (rc == SQLITE_DONE) return false;
        fail(rc, "Read local audio SQLite query");
    }

    std::optional<std::string_view> text(sqlite3_stmt* statement, int column) const {
        if (sqlite3_column_type(statement, column) == SQLITE_NULL) return std::nullopt;
        const auto* chars = reinterpret_cast<const char*>(sqlite3_column_text(statement, column));
        if (!chars) fail(SQLITE_NOMEM, "Read local audio text");
        return std::string_view(chars, sqlite3_column_bytes(statement, column));
    }

    std::string_view requiredText(sqlite3_stmt* statement, int column) const {
        if (auto value = text(statement, column)) return *value;
        throw std::runtime_error("Local audio row contains an unexpected NULL text value");
    }

    void startQuery() {
        source.verifyUnchanged();
        ++source.statistics.queryCount;
    }
};

Database::Database(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}
Database::~Database() = default;

std::unique_ptr<Database> Database::open(int fd, std::int64_t offset, std::int64_t length) {
    return std::unique_ptr<Database>(new Database(std::make_unique<Impl>(fd, offset, length)));
}

std::int64_t Database::sizeBytes() const {
    return impl_->source.length();
}

Statistics Database::statistics() const {
    return impl_->source.statistics;
}

void Database::queryEntries(std::string_view term, std::optional<std::string_view> reading,
                            const std::function<void(const Entry&)>& accept) {
    impl_->startQuery();
    sqlite3_stmt* query = reading ? impl_->byExpressionOrReading.get() : impl_->byExpression.get();
    const ResetStatement reset(query);
    impl_->bind(query, 1, term);
    if (reading) impl_->bind(query, 2, *reading);
    while (impl_->step(query)) {
        accept(Entry{
            .source = impl_->requiredText(query, 0),
            .expression = impl_->requiredText(query, 1),
            .reading = impl_->text(query, 2),
            .file = impl_->requiredText(query, 3),
            .display = impl_->text(query, 4).value_or(""),
        });
    }
    impl_->source.verifyUnchanged();
}

void Database::querySources(const std::function<void(std::string_view)>& accept) {
    impl_->startQuery();
    sqlite3_stmt* query = impl_->sources.get();
    const ResetStatement reset(query);
    while (impl_->step(query)) accept(impl_->requiredText(query, 0));
    impl_->source.verifyUnchanged();
}

bool Database::loadAudio(std::string_view source, std::string_view file,
                         const std::function<void(std::span<const std::byte>)>& accept) {
    impl_->startQuery();
    sqlite3_stmt* query = impl_->audio.get();
    const ResetStatement reset(query);
    impl_->bind(query, 1, source);
    impl_->bind(query, 2, file);
    const bool found = impl_->step(query);
    if (found) {
        if (sqlite3_column_type(query, 0) == SQLITE_NULL) {
            throw std::runtime_error("Local audio row contains a NULL audio payload");
        }
        const auto* data = static_cast<const std::byte*>(sqlite3_column_blob(query, 0));
        const int size = sqlite3_column_bytes(query, 0);
        if (!data && sqlite3_errcode(impl_->connection.get()) == SQLITE_NOMEM) {
            impl_->fail(SQLITE_NOMEM, "Read local audio payload");
        }
        accept(std::span(data, size));
    }
    impl_->source.verifyUnchanged();
    return found;
}

} // namespace hoshi::local_audio
