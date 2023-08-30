#include <jvmti.h>
#include <exception>
#include <functional>
#include <streambuf>

class JvmtiException : public std::exception
{
    jvmtiError _code;

public:
    JvmtiException(jvmtiError code) noexcept : _code(code)
    {}

    const char* what() const noexcept override
    {
        return "JVMTI threw exception";
    }

    jvmtiError code() const noexcept
    {
        return _code;
    }
};

static inline void throw_on_error(jvmtiError code)
{
    if(code != JVMTI_ERROR_NONE)
        throw JvmtiException(code);
}

template<typename T>
class JvmtiArray
{
    jvmtiEnv* jvmti_env;

protected:
    T* ptr = nullptr;
    jint len = 0;

public:
    JvmtiArray(jvmtiEnv* jvmti_env) : jvmti_env(jvmti_env) {}

    JvmtiArray(const JvmtiArray&) = delete;

    ~JvmtiArray()
    {
        throw_on_error(jvmti_env->Deallocate(reinterpret_cast<unsigned char*>(ptr)));
    }

    operator std::span<const T>() const noexcept
    {
        return {ptr, (size_t)len};
    }

    const T* begin() const
    {
        return ptr;
    }

    const T* end() const
    {
        return ptr + len;
    }
};

class LoadedClasses : public JvmtiArray<jclass>
{
public:
    LoadedClasses(jvmtiEnv* jvmti_env) : JvmtiArray<jclass>(jvmti_env)
    {
        throw_on_error(jvmti_env->GetLoadedClasses(&len, &ptr));
    }
};

class ClassFields : public JvmtiArray<jfieldID>
{
public:
    ClassFields(jvmtiEnv* jvmti_env, jclass klass) : JvmtiArray<jfieldID>(jvmti_env)
    {
        throw_on_error(jvmti_env->GetClassFields(klass, &len, &ptr));
    }
};

class JvmtiString
{
    jvmtiEnv* jvmti_env;
    char* str;

public:
    JvmtiString(jvmtiEnv* jvmti_env, char* str) : jvmti_env(jvmti_env), str(str) {}

    JvmtiString(const JvmtiString&) = delete;

    JvmtiString(JvmtiString&& o) : jvmti_env(o.jvmti_env), str(o.str)
    {
        o.str = nullptr;
    }

    operator const char*() const noexcept
    {
        return str;
    }

    ~JvmtiString()
    {
        throw_on_error(jvmti_env->Deallocate(reinterpret_cast<unsigned char*>(str)));
    }
};

struct FieldName
{
    JvmtiString name, signature, generic;

    static FieldName get(jvmtiEnv* jvmti_env, jclass klass, jfieldID field)
    {
        char *_name, *_signature, *_generic;
        throw_on_error(jvmti_env->GetFieldName(klass, field, &_name, &_signature, &_generic));
        return {{jvmti_env, _name}, {jvmti_env, _signature}, {jvmti_env, _generic}};
    }
};

struct ClassSignature
{
    JvmtiString signature, generic;

    static ClassSignature get(jvmtiEnv* jvmti_env, jclass klass)
    {
        char *_signature, *_generic;
        throw_on_error(jvmti_env->GetClassSignature(klass, &_signature, &_generic));
        return {{jvmti_env, _signature}, {jvmti_env, _generic}};
    }
};


template<typename ...TArgs>
void call_with_exception_handling(jvmtiEnv* jvmti_env, jvmtiError (jvmtiEnv::* memberfunc)(TArgs...), TArgs... args)
{
    jvmtiError code = std::invoke(memberfunc, jvmti_env, args...);
    if(code != JVMTI_ERROR_NONE)
        throw JvmtiException(code);
}

template<typename TReturn>
static inline TReturn swallow_cpp_exception_and_throw_java(jvmtiEnv* jvmti_env, std::invocable<const char*, const char*> auto&& thrower, std::invocable<> auto&& lambda)
{
    try {
        return lambda();
    } catch(const std::bad_alloc& rhs) {
        thrower("java/lang/OutOfMemoryError", rhs.what());
    } catch(const std::ios_base::failure& rhs) {
        thrower("java/io/IOException", rhs.what());
    } catch(const JvmtiException& e) {
        char msg[100];
        char* error_name;
        jvmtiError res = jvmti_env->GetErrorName(e.code(), &error_name);
        if(res == JVMTI_ERROR_NONE)
        {
            snprintf(msg, sizeof(msg), "JVMTI ERROR %u: %s", e.code(), error_name);
            jvmti_env->Deallocate(reinterpret_cast<unsigned char*>(error_name));
        }
        else
        {
            snprintf(msg, sizeof(msg), "JVMTI ERROR %u", e.code());
        }
        thrower("java/lang/Error", msg);
    } catch(const std::exception& e) {
        thrower("java/lang/Error", e.what());
    } catch(...) {
        thrower("java/lang/Error", "Unknown exception from HeapAssignmentTracingAgent");
    }

    return TReturn();
}

