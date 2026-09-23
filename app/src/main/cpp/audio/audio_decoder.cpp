#include <jni.h>
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
}
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <numeric>
#include <stdexcept>
#include <string>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>

namespace {
constexpr int rate = 16000;
void check(int value, const char* action) {
    if (value >= 0) return;
    char message[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(value, message, sizeof message);
    throw std::runtime_error(std::string(action) + ": " + message);
}
struct Decoder {
    int fd = -1;
    int64_t offset = 0, length = -1, position = 0;
    bool seekable = false, draining = false, finished = false, initialized = false;
    AVFormatContext* format = nullptr;
    AVIOContext* io = nullptr;
    AVCodecContext* codec = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;
    SwrContext* resampler = nullptr;
    AVStream* stream = nullptr;
    int streamIndex = -1;
    int inputRate = 0, inputChannels = 0, inputFormat = -1;
    int64_t expectedInput = 0, outputCursor = 0, wanted = 0, timelineOrigin = 0;
    std::vector<float> pending;
    size_t consumed = 0;

    ~Decoder() {
        swr_free(&resampler);
        av_frame_free(&frame);
        av_packet_free(&packet);
        avcodec_free_context(&codec);
        avformat_close_input(&format);
        if (io) { av_freep(&io->buffer); avio_context_free(&io); }
        if (fd >= 0) close(fd);
    }
    static int readBytes(void* opaque, uint8_t* buffer, int count) {
        auto& d = *static_cast<Decoder*>(opaque);
        if (d.length >= 0) count = static_cast<int>(std::min<int64_t>(count, d.length - d.position));
        if (count <= 0) return AVERROR_EOF;
        ssize_t n;
        do {
            n = d.seekable ? pread(d.fd, buffer, count, d.offset + d.position) : read(d.fd, buffer, count);
        } while (n < 0 && errno == EINTR);
        if (n < 0) return AVERROR(errno);
        if (n == 0) return AVERROR_EOF;
        d.position += n;
        return static_cast<int>(n);
    }
    static int64_t seekBytes(void* opaque, int64_t delta, int whence) {
        auto& d = *static_cast<Decoder*>(opaque);
        if (whence == AVSEEK_SIZE) return d.length >= 0 ? d.length : AVERROR(ENOSYS);
        whence &= ~AVSEEK_FORCE;
        int64_t base;
        if (whence == SEEK_SET) base = 0;
        else if (whence == SEEK_CUR) base = d.position;
        else if (whence == SEEK_END && d.length >= 0) base = d.length;
        else return AVERROR(EINVAL);
        if ((delta > 0 && base > INT64_MAX - delta) || (delta < 0 && delta < -base)) return AVERROR(EINVAL);
        int64_t next = base + delta;
        if (next < 0 || (d.length >= 0 && next > d.length)) return AVERROR(EINVAL);
        d.position = next;
        return next;
    }
    void openFile(int sourceFd, int64_t sourceOffset, int64_t sourceLength, double from) {
        if (sourceOffset < 0 || sourceLength < -1 || !std::isfinite(from) || from < 0 || from > 1e10)
            throw std::runtime_error("Invalid audio range");
        fd = dup(sourceFd);
        if (fd < 0) throw std::runtime_error("Cannot duplicate audio descriptor");
        offset = sourceOffset; length = sourceLength;
        seekable = lseek(fd, 0, SEEK_CUR) >= 0;
        if (!seekable && offset != 0) throw std::runtime_error("Non-seekable audio has an offset");
        struct stat info{};
        if (length < 0 && fstat(fd, &info) == 0 && S_ISREG(info.st_mode)) {
            if (offset > info.st_size) throw std::runtime_error("Invalid audio descriptor offset");
            length = info.st_size - offset;
        }
        if (length >= 0 && offset > INT64_MAX - length) throw std::runtime_error("Invalid audio descriptor length");
        auto* buffer = static_cast<unsigned char*>(av_malloc(32768));
        if (!buffer) throw std::bad_alloc();
        io = avio_alloc_context(buffer, 32768, 0, this, readBytes, nullptr, seekable ? seekBytes : nullptr);
        if (!io) { av_free(buffer); throw std::bad_alloc(); }
        format = avformat_alloc_context();
        if (!format) throw std::bad_alloc();
        format->pb = io;
        format->flags |= AVFMT_FLAG_CUSTOM_IO;
        check(avformat_open_input(&format, nullptr, nullptr, nullptr), "Open audio");
        check(avformat_find_stream_info(format, nullptr), "Inspect audio");
        // Match Android duration probing and the previous decoder: both use
        // the first audio track, even when another track is marked default.
        for (unsigned i = 0; i < format->nb_streams; ++i) {
            if (format->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
                streamIndex = static_cast<int>(i);
                break;
            }
        }
        if (streamIndex < 0) throw std::runtime_error("No audio track");
        stream = format->streams[streamIndex];
        // MP3 timestamps include encoder priming; its first audible sample is
        // time zero. Container timestamps (e.g. a delayed MP4 track) stay intact.
        if (std::strcmp(format->iformat->name, "mp3") == 0 && stream->start_time != AV_NOPTS_VALUE)
            timelineOrigin = stream->start_time;
        const auto* implementation = avcodec_find_decoder(stream->codecpar->codec_id);
        if (!implementation) throw std::runtime_error("Unsupported audio codec");
        codec = avcodec_alloc_context3(implementation);
        if (!codec) throw std::bad_alloc();
        check(avcodec_parameters_to_context(codec, stream->codecpar), "Read audio format");
        // Decoder delay/discard padding adjusts frame PTS in this time base.
        // Without it, MP3/Opus priming is trimmed twice by the media clock.
        codec->pkt_timebase = stream->time_base;
        codec->thread_count = 1;
        check(avcodec_open2(codec, implementation, nullptr), "Open audio decoder");
        packet = av_packet_alloc(); frame = av_frame_alloc();
        if (!packet || !frame) throw std::bad_alloc();
        wanted = static_cast<int64_t>(std::ceil(from * rate - 1e-8));
        // Decode preroll for AAC overlap, MP3 bit reservoirs and Opus seek convergence.
        if (from > 1.0 && seekable) {
            int64_t target = av_rescale_q(static_cast<int64_t>((from - 1.0) * 1000000), AV_TIME_BASE_Q, stream->time_base) + timelineOrigin;
            check(avformat_seek_file(format, streamIndex, INT64_MIN, target, target, AVSEEK_FLAG_BACKWARD), "Seek audio");
            avcodec_flush_buffers(codec);
        }
    }
    void initialize() {
        inputRate = frame->sample_rate; inputChannels = frame->ch_layout.nb_channels; inputFormat = frame->format;
        if (inputRate < 8000 || inputRate > 192000 || inputChannels < 1 || inputChannels > 32)
            throw std::runtime_error("Unsupported decoded audio format");
        AVChannelLayout mono = AV_CHANNEL_LAYOUT_MONO;
        check(swr_alloc_set_opts2(&resampler, &mono, AV_SAMPLE_FMT_FLT, rate,
            &frame->ch_layout, static_cast<AVSampleFormat>(inputFormat), inputRate, 0, nullptr), "Create resampler");
        std::vector<double> matrix(inputChannels, 1.0 / inputChannels);
        check(swr_set_matrix(resampler, matrix.data(), inputChannels), "Set mono mixing");
        check(swr_init(resampler), "Initialize resampler");
        if (frame->best_effort_timestamp == AV_NOPTS_VALUE) throw std::runtime_error("Missing audio timestamp");
        expectedInput = av_rescale_q(frame->best_effort_timestamp - timelineOrigin, stream->time_base, AVRational{1, inputRate});
        // Start the filter at a common input/output sample boundary. Otherwise a
        // seek changes its fractional phase and rounds away part of one sample.
        const int period = inputRate / std::gcd(inputRate, rate);
        const int prefix = static_cast<int>((expectedInput % period + period) % period);
        outputCursor = av_rescale_q(expectedInput - prefix, AVRational{1, inputRate}, AVRational{1, rate});
        if (outputCursor > wanted) {
            if (outputCursor - wanted > 10LL * rate) throw std::runtime_error("Discontinuous initial audio timestamp");
            pending.insert(pending.end(), static_cast<size_t>(outputCursor - wanted), 0.0f);
            wanted = outputCursor;
        }
        check(swr_inject_silence(resampler, prefix), "Align resampler clock");
        initialized = true;
    }
    void append(const float* samples, int count) {
        int skip = static_cast<int>(std::clamp<int64_t>(wanted - outputCursor, 0, count));
        for (int i = skip; i < count; ++i) pending.push_back((std::isfinite(samples[i]) ? std::clamp(samples[i], -1.0f, 1.0f) : 0.0f));
        outputCursor += count;
        wanted = std::max(wanted, outputCursor);
    }
    void convert(const uint8_t** input, int count) {
        int capacity = static_cast<int>(av_rescale_rnd(swr_get_delay(resampler, inputRate) + count, rate, inputRate, AV_ROUND_UP)) + 32;
        std::vector<float> output(capacity);
        uint8_t* target[] = {reinterpret_cast<uint8_t*>(output.data())};
        int n = swr_convert(resampler, target, capacity, input, count);
        check(n, "Resample audio");
        append(output.data(), n);
    }
    void processFrame() {
        if (!initialized) initialize();
        if (inputRate != frame->sample_rate || inputChannels != frame->ch_layout.nb_channels || inputFormat != frame->format)
            throw std::runtime_error("Audio format changed during decoding");
        if (frame->nb_samples < 0 || frame->nb_samples > inputRate * 2 || frame->best_effort_timestamp == AV_NOPTS_VALUE)
            throw std::runtime_error("Invalid decoded audio frame");
        int64_t start = av_rescale_q(frame->best_effort_timestamp - timelineOrigin, stream->time_base, AVRational{1, inputRate});
        int64_t gap = start - expectedInput;
        if (gap > inputRate * 10LL) throw std::runtime_error("Discontinuous audio timestamps");
        if (gap > 0) {
            check(swr_inject_silence(resampler, static_cast<int>(gap)), "Preserve audio gap");
            expectedInput += gap;
        }
        int skip = static_cast<int>(std::clamp<int64_t>(expectedInput - start, 0, frame->nb_samples));
        int n = frame->nb_samples - skip;
        if (n > 0) {
            bool planar = av_sample_fmt_is_planar(static_cast<AVSampleFormat>(inputFormat));
            int planes = planar ? inputChannels : 1;
            int stride = av_get_bytes_per_sample(static_cast<AVSampleFormat>(inputFormat)) * (planar ? 1 : inputChannels);
            std::vector<const uint8_t*> data(planes);
            for (int i = 0; i < planes; ++i) data[i] = frame->extended_data[i] + skip * stride;
            convert(data.data(), n);
            expectedInput += n;
        }
        av_frame_unref(frame);
    }
    void advance() {
        while (!finished) {
            int result = avcodec_receive_frame(codec, frame);
            if (result >= 0) { processFrame(); return; }
            if (result == AVERROR_EOF) {
                if (initialized) convert(nullptr, 0);
                finished = true;
                return;
            }
            if (result != AVERROR(EAGAIN)) check(result, "Decode audio");
            if (draining) throw std::runtime_error("Audio decoder did not finish");
            do {
                av_packet_unref(packet);
                result = av_read_frame(format, packet);
            } while (result >= 0 && packet->stream_index != streamIndex);
            if (result == AVERROR_EOF) {
                draining = true;
                check(avcodec_send_packet(codec, nullptr), "Finish audio decoder");
            } else {
                check(result, "Read audio packet");
                check(avcodec_send_packet(codec, packet), "Submit audio packet");
            }
        }
    }
    jfloatArray next(JNIEnv* env) {
        while (pending.size() - consumed < 4096 && !finished) advance();
        int count = static_cast<int>(std::min<size_t>(4096, pending.size() - consumed));
        if (!count) return nullptr;
        jfloatArray result = env->NewFloatArray(count);
        if (!result) return nullptr;
        env->SetFloatArrayRegion(result, 0, count, pending.data() + consumed);
        consumed += count;
        if (consumed == pending.size()) { pending.clear(); consumed = 0; }
        else if (consumed >= 4096) { pending.erase(pending.begin(), pending.begin() + consumed); consumed = 0; }
        return result;
    }
};
void fail(JNIEnv* env, const std::exception& error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
}
}
extern "C" JNIEXPORT jlong JNICALL Java_moe_antimony_hoshi_features_sasayaki_transcription_NativeSasayakiAudio_open(JNIEnv* env, jobject, jint fd, jlong offset, jlong length, jdouble from) {
    try { auto decoder = std::make_unique<Decoder>(); decoder->openFile(fd, offset, length, from); return reinterpret_cast<jlong>(decoder.release()); }
    catch (const std::exception& error) { fail(env, error); return 0; }
}
extern "C" JNIEXPORT jfloatArray JNICALL Java_moe_antimony_hoshi_features_sasayaki_transcription_NativeSasayakiAudio_read(JNIEnv* env, jobject, jlong ptr) {
    try { if (!ptr) throw std::runtime_error("Audio decoder is closed"); return reinterpret_cast<Decoder*>(ptr)->next(env); }
    catch (const std::exception& error) { fail(env, error); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_moe_antimony_hoshi_features_sasayaki_transcription_NativeSasayakiAudio_close(JNIEnv*, jobject, jlong ptr) { delete reinterpret_cast<Decoder*>(ptr); }
