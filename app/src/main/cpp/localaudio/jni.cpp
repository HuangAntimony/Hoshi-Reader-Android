#include "database.h"

#include <jni.h>

#include <limits>
#include <stdexcept>
#include <string>

using hoshi::local_audio::Database;
using hoshi::local_audio::Entry;

namespace {

struct JavaException {};

void checkJava(JNIEnv* env) {
    if (env->ExceptionCheck()) throw JavaException{};
}

template<class Ref>
class LocalRef {
public:
    LocalRef(JNIEnv* env, Ref value) : env_(env), value_(value) { checkJava(env_); }
    ~LocalRef() { if (value_) env_->DeleteLocalRef(value_); }
    LocalRef(const LocalRef&) = delete;
    LocalRef& operator=(const LocalRef&) = delete;

    Ref get() const { return value_; }
    Ref release() {
        Ref value = value_;
        value_ = nullptr;
        return value;
    }

private:
    JNIEnv* env_;
    Ref value_;
};

jmethodID method(JNIEnv* env, jclass type, const char* name, const char* signature) {
    const auto id = env->GetMethodID(type, name, signature);
    checkJava(env);
    return id;
}

jbyteArray byteArray(JNIEnv* env, const void* data, std::size_t size) {
    if (size > std::numeric_limits<jsize>::max()) throw std::runtime_error("Local audio value is too large");
    const jsize length = static_cast<jsize>(size);
    LocalRef bytes(env, env->NewByteArray(length));
    if (length > 0) {
        env->SetByteArrayRegion(bytes.get(), 0, length, static_cast<const jbyte*>(data));
        checkJava(env);
    }
    return bytes.release();
}

class Utf8Strings {
public:
    explicit Utf8Strings(JNIEnv* env)
        : env_(env), type_(env, env->FindClass("java/lang/String")),
          utf8_(env, env->NewStringUTF("UTF-8")),
          constructor_(method(env, type_.get(), "<init>", "([BLjava/lang/String;)V")) {}

    jclass type() const { return type_.get(); }

    jstring create(std::string_view text) const {
        const LocalRef bytes(env_, byteArray(env_, text.data(), text.size()));
        auto value = static_cast<jstring>(env_->NewObject(type_.get(), constructor_, bytes.get(), utf8_.get()));
        checkJava(env_);
        return value;
    }

private:
    JNIEnv* env_;
    LocalRef<jclass> type_;
    LocalRef<jstring> utf8_;
    jmethodID constructor_;
};

class JavaList {
public:
    explicit JavaList(JNIEnv* env)
        : env_(env), type_(env, env->FindClass("java/util/ArrayList")),
          add_(method(env, type_.get(), "add", "(Ljava/lang/Object;)Z")),
          value_(env, env->NewObject(type_.get(), method(env, type_.get(), "<init>", "()V"))) {}

    void add(jobject element) const {
        env_->CallBooleanMethod(value_.get(), add_, element);
        checkJava(env_);
    }

    jobject release() { return value_.release(); }

private:
    JNIEnv* env_;
    LocalRef<jclass> type_;
    jmethodID add_;
    LocalRef<jobject> value_;
};

std::string readBytes(JNIEnv* env, jbyteArray bytes) {
    if (!bytes) throw std::runtime_error("Missing local audio query argument");
    const jsize size = env->GetArrayLength(bytes);
    std::string result(size, '\0');
    if (size > 0) {
        env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(result.data()));
        checkJava(env);
    }
    return result;
}

void throwIOException(JNIEnv* env, std::string_view message) noexcept {
    try {
        const Utf8Strings strings(env);
        const LocalRef type(env, env->FindClass("java/io/IOException"));
        const LocalRef text(env, strings.create(message));
        const auto ctor = method(env, type.get(), "<init>", "(Ljava/lang/String;)V");
        const LocalRef error(env, static_cast<jthrowable>(env->NewObject(type.get(), ctor, text.get())));
        env->Throw(error.get());
    } catch (const JavaException&) {
        // Preserve the pending JVM exception.
    }
}

template<class Result, class Action>
Result call(JNIEnv* env, Action action) noexcept {
    try {
        return action();
    } catch (const JavaException&) {
        // Preserve the pending JVM exception.
    } catch (const std::exception& error) {
        throwIOException(env, error.what());
    }
    return Result{};
}

Database* database(jlong handle) {
    return reinterpret_cast<Database*>(static_cast<std::uintptr_t>(handle));
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_moe_antimony_hoshi_features_audio_LocalAudioNativeBridge_open(
    JNIEnv* env, jobject, jint fd, jlong offset, jlong length) {
    return call<jlong>(env, [&] {
        auto connection = Database::open(fd, offset, length);
        return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(connection.release()));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_moe_antimony_hoshi_features_audio_LocalAudioNativeBridge_sizeBytes(
    JNIEnv*, jobject, jlong handle) {
    return database(handle)->sizeBytes();
}

extern "C" JNIEXPORT jobject JNICALL
Java_moe_antimony_hoshi_features_audio_LocalAudioNativeBridge_queryEntries(
    JNIEnv* env, jobject, jlong handle, jbyteArray term, jbyteArray reading) {
    return call<jobject>(env, [&] {
        const auto expression = readBytes(env, term);
        const auto pronunciation = reading ? std::optional(readBytes(env, reading)) : std::nullopt;
        const Utf8Strings strings(env);
        JavaList entries(env);
        database(handle)->queryEntries(expression, pronunciation, [&](const Entry& entry) {
            const LocalRef row(env, env->NewObjectArray(5, strings.type(), nullptr));
            const std::optional<std::string_view> values[] = {
                entry.source, entry.expression, entry.reading, entry.file, entry.display,
            };
            for (jsize column = 0; column < 5; ++column) {
                if (!values[column]) continue;
                const LocalRef value(env, strings.create(*values[column]));
                env->SetObjectArrayElement(row.get(), column, value.get());
                checkJava(env);
            }
            entries.add(row.get());
        });
        return entries.release();
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_moe_antimony_hoshi_features_audio_LocalAudioNativeBridge_querySources(
    JNIEnv* env, jobject, jlong handle) {
    return call<jobject>(env, [&] {
        const Utf8Strings strings(env);
        JavaList sources(env);
        database(handle)->querySources([&](std::string_view source) {
            const LocalRef value(env, strings.create(source));
            sources.add(value.get());
        });
        return sources.release();
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_moe_antimony_hoshi_features_audio_LocalAudioNativeBridge_loadAudio(
    JNIEnv* env, jobject, jlong handle, jbyteArray source, jbyteArray file) {
    return call<jbyteArray>(env, [&] {
        jbyteArray result = nullptr;
        database(handle)->loadAudio(readBytes(env, source), readBytes(env, file), [&](auto bytes) {
            result = byteArray(env, bytes.data(), bytes.size());
        });
        return result;
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_moe_antimony_hoshi_features_audio_LocalAudioNativeBridge_statistics(
    JNIEnv* env, jobject, jlong handle) {
    return call<jlongArray>(env, [&] {
        const auto stats = database(handle)->statistics();
        const jlong values[] = {stats.readCalls, stats.readBytes, stats.prepareCount, stats.queryCount};
        LocalRef result(env, env->NewLongArray(4));
        env->SetLongArrayRegion(result.get(), 0, 4, values);
        checkJava(env);
        return result.release();
    });
}

extern "C" JNIEXPORT void JNICALL
Java_moe_antimony_hoshi_features_audio_LocalAudioNativeBridge_close(
    JNIEnv*, jobject, jlong handle) {
    delete database(handle);
}
