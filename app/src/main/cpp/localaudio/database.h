#ifndef HOSHI_LOCAL_AUDIO_DATABASE_H
#define HOSHI_LOCAL_AUDIO_DATABASE_H

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <span>
#include <string_view>

namespace hoshi::local_audio {

struct Entry {
    std::string_view source;
    std::string_view expression;
    std::optional<std::string_view> reading;
    std::string_view file;
    std::string_view display;
};

struct Statistics {
    std::int64_t readCalls = 0;
    std::int64_t readBytes = 0;
    std::int64_t prepareCount = 0;
    std::int64_t queryCount = 0;
};

// The caller serializes operations. Callback values are valid only during the callback.
class Database {
public:
    static std::unique_ptr<Database> open(int fd, std::int64_t offset, std::int64_t length);
    ~Database();

    std::int64_t sizeBytes() const;
    Statistics statistics() const;
    void queryEntries(std::string_view term, std::optional<std::string_view> reading,
                      const std::function<void(const Entry&)>& accept);
    void querySources(const std::function<void(std::string_view)>& accept);
    bool loadAudio(std::string_view source, std::string_view file,
                   const std::function<void(std::span<const std::byte>)>& accept);

private:
    struct Impl;
    explicit Database(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

} // namespace hoshi::local_audio

#endif
