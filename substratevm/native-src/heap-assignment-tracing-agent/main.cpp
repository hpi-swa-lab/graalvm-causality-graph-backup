#include <jvmti.h>
#include <iostream>
#include <span>
#include <cstring>
#include <cassert>
#include <vector>
#include <fstream>
#include "settings.h"
#include <ranges>
#include <unordered_map>
#include <memory>
#include <atomic>
#include <iterator>
#include <variant>

static bool check_jvmti_error(jvmtiError errorcode, const char* code, const char* filename, int line)
{
    bool error = errorcode != JVMTI_ERROR_NONE;
    if (error)
        std::cerr << "JVMTI ERROR " << errorcode << " at " << filename << ':' << line << ": \"" << code << '"' << std::endl;
    return error;
}

#define check_code(retcode, expr) if(check_jvmti_error(expr, #expr, __FILE__, __LINE__)) { return retcode; }
#define check(expr) if(check_jvmti_error(expr, #expr, __FILE__, __LINE__)) { exit(1); }

using namespace std;




class JniString
{
    JNIEnv* env;
    jstring o;
    const char* chars;

public:
    JniString(JNIEnv* env, jstring o) : env(env), o(o)
    {
        jboolean isCopy;
        chars = env->GetStringUTFChars(o, &isCopy);
    }

    friend std::ostream& operator<<(std::ostream& out, const JniString& str)
    {
        out << str.chars;
        return out;
    }

    operator bool() const
    {
        return chars != nullptr;
    }

    operator string() const
    {
        return {chars};
    }

    ~JniString()
    {
        env->ReleaseStringUTFChars(o, chars);
    }
};

static string javaObjectToString(JNIEnv* env, jobject o)
{
    jclass clazz = env->GetObjectClass(o);
    jmethodID m = env->GetMethodID(clazz, "toString", "()Ljava/lang/String;");
    jstring str = (jstring)env->CallObjectMethod(o, m);
    return JniString(env, str);
}

bool add_clinit_hook(jvmtiEnv* jvmti_env, const unsigned char* src, jint src_len, unsigned char** dst_ptr, jint* dst_len_ptr);

static void JNICALL onFieldModification(
        jvmtiEnv *jvmti_env,
        JNIEnv* jni_env,
        jthread thread,
        jmethodID method,
        jlocation location,
        jclass field_klass,
        jobject object,
        jfieldID field,
        char signature_type,
        jvalue new_value);

static void JNICALL onClassPrepare(
        jvmtiEnv *jvmti_env,
        JNIEnv* jni_env,
        jthread thread,
        jclass klass);

static void JNICALL onVMInit(jvmtiEnv *jvmti_env, JNIEnv* jni_env, jthread thread);

static void JNICALL onFramePop(
        jvmtiEnv *jvmti_env,
        JNIEnv* jni_env,
        jthread thread,
        jmethodID method,
        jboolean was_popped_by_exception);

static void JNICALL onClassFileLoad(
        jvmtiEnv *jvmti_env,
        JNIEnv* jni_env,
        jclass class_being_redefined,
        jobject loader,
        const char* name,
        jobject protection_domain,
        jint class_data_len,
        const unsigned char* class_data,
        jint* new_class_data_len,
        unsigned char** new_class_data);

static void JNICALL onThreadStart(
        jvmtiEnv *jvmti_env,
        JNIEnv* jni_env,
        jthread thread);

static void JNICALL onThreadEnd(
        jvmtiEnv *jvmti_env,
        JNIEnv* jni_env,
        jthread thread);

static void JNICALL onObjectFree(
        jvmtiEnv *jvmti_env,
        jlong tag);

#if LOG_METHOD_ENTRY_EXIT_EVENTS
static void JNICALL onMethodEntry
        (jvmtiEnv *jvmti_env,
         JNIEnv* jni_env,
         jthread thread,
         jmethodID method);

static void JNICALL onMethodExit
        (jvmtiEnv *jvmti_env,
         JNIEnv* jni_env,
         jthread thread,
         jmethodID method,
         jboolean was_popped_by_exception,
         jvalue return_value);
#endif


class AgentThreadContext
{
    vector<jobject> runningClassInitializations;

public:
    static AgentThreadContext* from_thread(jvmtiEnv* jvmti_env, jthread t)
    {
        AgentThreadContext* tc;
        check(jvmti_env->GetThreadLocalStorage(t, (void**)&tc));

        if(!tc)
        {
#if LOG
            cerr << "Thread had no initialized context!" << endl;
#endif
            tc = new AgentThreadContext();
            check(jvmti_env->SetThreadLocalStorage(t, tc));
        }

        return tc;
    }

    void clinit_push(JNIEnv* env, jobject clazz)
    {
        runningClassInitializations.push_back(env->NewGlobalRef(clazz));
    }

    void clinit_pop(JNIEnv* env)
    {
        assert(!runningClassInitializations.empty());
        runningClassInitializations.pop_back();
        // Leaking jclass global objects since they serve in ObjectContext...
    }

    [[nodiscard]] jobject clinit_top() const
    {
        return runningClassInitializations.back();
    }

    [[nodiscard]] bool clinit_empty() const
    {
        return runningClassInitializations.empty();
    }
};

struct ObjectContext
{
    static inline uint64_t next_id = 0;
    static inline mutex creation_mutex;

    // This id is unique even after collection by GC
    uint64_t id;
    jobject allocReason = nullptr;

    static ObjectContext* create(jvmtiEnv* jvmti_env, JNIEnv* env, jobject o);

protected:
    ObjectContext() = default;

public:
    virtual ~ObjectContext() = default;

    static ObjectContext* get(jvmtiEnv* jvmti_env, jobject o)
    {
        ObjectContext* oc;
        check(jvmti_env->GetTag(o, (jlong*)&oc));
        return oc;
    }

    static ObjectContext* get_or_create(jvmtiEnv* jvmti_env, JNIEnv* env, jobject o)
    {
        ObjectContext* oc = get(jvmti_env, o);

        if(!oc)
            oc = create(jvmti_env, env, o);

        return oc;
    }
};

struct Write
{
    uint64_t object_id;
    jobject reason;
};

template<typename T>
class MonotonicConcurrentList
{
    struct Element
    {
        Element* prev;
        T data;

        Element(T data) : data(data) {}
    };

    atomic<Element*> head = nullptr;

public:
    class iterator
    {
        friend class MonotonicConcurrentList;
        Element* cur;

        iterator(Element* cur) : cur(cur) {}

    public:
        using value_type = T;
        using difference_type = std::ptrdiff_t;
        using iterator_category = std::input_iterator_tag;

        bool operator==(default_sentinel_t sentinel)
        {
            return cur == nullptr;
        }

        bool operator!=(default_sentinel_t sentinel)
        {
            return cur != nullptr;
        }

        iterator& operator++()
        {
            cur = cur->prev;
            return *this;
        }

        void operator++(int) { (*this)++; }

        T& operator*() { return cur->data; }
        T& operator->() { return cur->data; }
    };

    static_assert(std::input_or_output_iterator<iterator>);

    MonotonicConcurrentList() = default;

    void push(T data)
    {
        Element* new_elem = new Element(data);
        Element* cur_head = head;

        do
        {
            new_elem->prev = cur_head;
        }
        while(!head.compare_exchange_weak(cur_head, new_elem));
    }

    iterator begin()
    {
        return {head};
    }

    default_sentinel_t end()
    {
        return {};
    }
};

class WriteHistory
{
    MonotonicConcurrentList<Write> history;

public:
    void add(ObjectContext* o, jobject reason)
    {
        history.push({o->id, reason});
    }

    jobject lookup(ObjectContext* writtenVal)
    {
        uint64_t id = writtenVal->id;
        for(const Write& write : history)
        {
            if(write.object_id == id)
                return write.reason;
        }
        return nullptr;
    }
};


struct ClassInfo
{
    unordered_map<jfieldID, size_t> nonstatic_field_indices;
    unordered_map<jfieldID, size_t> static_field_indices;

    ClassInfo(jvmtiEnv* jvmti_env, JNIEnv* jni_env, jclass klass)
    {
        do
        {
            jint count;
            jfieldID* fields;
            check(jvmti_env->GetClassFields(klass, &count, &fields));

            for(size_t i = 0; i < count; i++)
            {
                char* field_name;
                char* field_signature;
                char* field_generic;

                check(jvmti_env->GetFieldName(klass, fields[i], &field_name, &field_signature, &field_generic));

                // Don't care for primitive types
                if(field_signature[0] != 'L' && field_signature[0] != '[')
                    continue;

                jint modifiers;
                check(jvmti_env->GetFieldModifiers(klass, fields[i], &modifiers));

                if(modifiers & 8 /* ACC_STATIC */)
                    static_field_indices.emplace(fields[i], static_field_indices.size());
                else
                    nonstatic_field_indices.emplace(fields[i], nonstatic_field_indices.size());
            }

            klass = jni_env->GetSuperclass(klass);
        }
        while(klass);
    }

    size_t get_nonstatic_field_index(jfieldID field) const
    {
        auto it = nonstatic_field_indices.find(field);
        assert(it != nonstatic_field_indices.end());
        return it->second;
    }
};


struct NonArrayObjectContext : public ObjectContext
{
    shared_ptr<const ClassInfo> cc;
    vector<WriteHistory> fields_history;

public:
    ~NonArrayObjectContext() override = default;

    NonArrayObjectContext(shared_ptr<const ClassInfo> cc);

    void registerWrite(jfieldID field, ObjectContext* newVal, jobject reason);

    jobject getWriteReason(jfieldID field, ObjectContext* writtenVal);
};

class ClassContext : public NonArrayObjectContext
{
    struct LazyData
    {
        shared_ptr<const ClassInfo> info;
        unique_ptr<WriteHistory[]> fields_history = nullptr;

        LazyData(shared_ptr<const ClassInfo> info) : info(std::move(info)), fields_history(new WriteHistory[this->info->static_field_indices.size()]())
        {
        }

        void registerStaticWrite(jfieldID field, ObjectContext* newVal, jobject reason)
        {
            auto lookup_res = info->static_field_indices.find(field);

            if(lookup_res != info->static_field_indices.end())
            {
                assert(lookup_res->second < info->static_field_indices.size());
                fields_history[lookup_res->second].add(newVal, reason);
            }
            else
            {
                assert(false);
#if LOG
                cerr << "!info->static_field_indices.contains(field) for class " << internal_name << " and field " << (uint64_t)field << endl;
#endif
            }
        }

        jobject getStaticFieldReason(jfieldID field, ObjectContext* writtenVal)
        {
            auto it = info->static_field_indices.find(field);
            assert(it != info->static_field_indices.end());
            assert(it->second < info->static_field_indices.size());
            return fields_history[it->second].lookup(writtenVal);
        }
    };

    jweak class_object;
    atomic<LazyData*> lazy = nullptr;

    LazyData& data(jvmtiEnv* jvmti_env, JNIEnv* jni_env)
    {
        if(lazy)
            return *lazy;

        jclass clazz = (jclass)jni_env->NewLocalRef(class_object);
        assert(clazz && "Class object has been collected!");

        // Race condition: Since pointer types are atomically assignable, the worst case is a (minor) memory leak here.
        LazyData* expected = nullptr;
        LazyData* desired = new LazyData(make_shared<ClassInfo>(jvmti_env, jni_env, clazz));
        jni_env->DeleteLocalRef(clazz);
        bool uncontended = lazy.compare_exchange_strong(expected, desired);
        if(uncontended)
            return *desired;

        delete desired;
        return *expected;
    }

public:
    ClassContext(jvmtiEnv* jvmti_env, JNIEnv* jni_env, jclass klass, shared_ptr<const ClassInfo> declaring_info, shared_ptr<const ClassInfo> own_info = {}) :
        NonArrayObjectContext(std::move(declaring_info)),
        class_object(jni_env->NewWeakGlobalRef(klass))
    {
        if(own_info)
            lazy = new LazyData(std::move(own_info));
    }

    ~ClassContext() override
    {
        delete lazy;
        // TODO: Remove leak of class_object
    }

    void registerStaticWrite(jvmtiEnv* jvmti_env, JNIEnv* jni_env, jfieldID field, ObjectContext* newVal, jobject reason)
    {
        data(jvmti_env, jni_env).registerStaticWrite(field, newVal, reason);
    }

    jobject getStaticFieldReason(jvmtiEnv* jvmti_env, JNIEnv* jni_env, jfieldID field, ObjectContext* writtenVal)
    {
        return data(jvmti_env, jni_env).getStaticFieldReason(field, writtenVal);
    }

    shared_ptr<const ClassInfo> info(jvmtiEnv* jvmti_env, JNIEnv* jni_env)
    {
        return data(jvmti_env, jni_env).info;
    }

    static ClassContext* get_or_create(jvmtiEnv* jvmti_env, JNIEnv* jni_env, jclass klass)
    {
        return dynamic_cast<ClassContext*>(ObjectContext::get_or_create(jvmti_env, jni_env, klass));
    }

    jobject made_reachable_by = nullptr;
};

NonArrayObjectContext::NonArrayObjectContext(shared_ptr<const ClassInfo> cc) : cc(std::move(cc)), fields_history(this->cc->nonstatic_field_indices.size())
{}

void NonArrayObjectContext::registerWrite(jfieldID field, ObjectContext* newVal, jobject reason)
{
    fields_history.at(cc->get_nonstatic_field_index(field)).add(newVal, reason);
}

jobject NonArrayObjectContext::getWriteReason(jfieldID field, ObjectContext* writtenVal)
{
    return fields_history.at(cc->get_nonstatic_field_index(field)).lookup(writtenVal);
}


struct ArrayObjectContext : public ObjectContext
{
    vector<WriteHistory> elements_history;

public:
    ArrayObjectContext(size_t array_length) : elements_history(array_length)
    { }

    ~ArrayObjectContext() override = default;

    void registerWrite(jint index, ObjectContext* newVal, jobject reason)
    {
        assert(index >= 0 && index < elements_history.size());
        elements_history[index].add(newVal, reason);
    }

    jobject getWriteReason(jint index, ObjectContext* writtenVal)
    {
        assert(index >= 0 && index < elements_history.size());
        return elements_history[index].lookup(writtenVal);
    }
};

ObjectContext* ObjectContext::create(jvmtiEnv* jvmti_env, JNIEnv* env, jobject o)
{
    jclass oClass = env->GetObjectClass(o);
    char* signature;
    char* generic;
    check(jvmti_env->GetClassSignature(oClass, &signature, &generic));

    ObjectContext* oc;

    if(env->IsSameObject(oClass, o))
    {
        auto info = make_shared<ClassInfo>(jvmti_env, env, oClass);
        oc = new ClassContext(jvmti_env, env, oClass, info, info);
    }
    else if(signature[0] == 'L')
    {
        ClassContext* cc = ClassContext::get_or_create(jvmti_env, env, oClass);
        shared_ptr<const ClassInfo> ci = cc->info(jvmti_env, env);

        if(std::strcmp(signature, "Ljava/lang/Class;") == 0)
        {
            oc = new ClassContext(jvmti_env, env, (jclass)o, std::move(ci));
        }
        else
        {
            oc = new NonArrayObjectContext(std::move(ci));
        }
    }
    else if(signature[0] == '[')
    {
        size_t array_length = env->GetArrayLength((jarray)o);
        oc = new ArrayObjectContext(array_length);
    }
    else
    {
        assert(false);
    }

    {
        lock_guard<mutex> l(ObjectContext::creation_mutex);
        jlong oldTag;
        check(jvmti_env->GetTag(o, &oldTag));

        if(oldTag)
        {
#if LOG
            cerr << "Concurrent ObjectContext creation!\n";
#endif
            delete oc;
            oc = (ObjectContext*)oldTag;
        }
        else
        {
            oc->id = next_id++;
            check(jvmti_env->SetTag(o, (jlong)oc));
        }
    }

    return oc;
}



static void addToTracingStack(jvmtiEnv* jvmti_env, JNIEnv* env, jthread thread, jobject reason)
{
    AgentThreadContext* tc = AgentThreadContext::from_thread(jvmti_env, thread);

#if BREAKPOINTS_ENABLE
    if(tc->clinit_empty())
    {
        check(jvmti_env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_FIELD_MODIFICATION, thread));
#if LOG_METHOD_ENTRY_EXIT_EVENTS
        check(jvmti_env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_ENTRY, thread));
        check(jvmti_env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_EXIT, thread));
#endif
    }
#endif

#if LOG || PRINT_CLINIT_HEAP_WRITES

    char inner_clinit_name[1024];
    get_class_name(jvmti_env, type, inner_clinit_name);

    char outer_clinit_name[1024];

    if(tc->clinit_empty())
        outer_clinit_name[0] = 0;
    else
        get_class_name(jvmti_env, tc->clinit_top(), outer_clinit_name);

    if(LOG || (strcmp(inner_clinit_name, outer_clinit_name) != 0))
    {
        cerr << outer_clinit_name << ": " << inner_clinit_name << ".<clinit>()\n";
    }
#endif

    jobject made_reachable_by = tc->clinit_empty() ? nullptr : tc->clinit_top();
    tc->clinit_push(env, reason);

    if(made_reachable_by && reason)
    {
        // Use tc->clinit_top because thats a global ref now...
        ObjectContext* oc = ObjectContext::get_or_create(jvmti_env, env, tc->clinit_top());

        if(auto* cc = dynamic_cast<ClassContext*>(oc))
        {
            assert(!cc->made_reachable_by);
            cc->made_reachable_by = made_reachable_by;
        }
    }
}

static void removeFromTracingStack(jvmtiEnv* jvmti_env, JNIEnv* env, jthread thread, jobject reason)
{
    AgentThreadContext* tc = AgentThreadContext::from_thread(jvmti_env, thread);

    jobject topReason = tc->clinit_top();
    assert(env->IsSameObject(topReason, reason));
    tc->clinit_pop(env);

#if BREAKPOINTS_ENABLE
    if(tc->clinit_empty())
    {
        check(jvmti_env->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_FIELD_MODIFICATION, thread));
#if LOG_METHOD_ENTRY_EXIT_EVENTS
        check(jvmti_env->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_ENTRY, thread));
        check(jvmti_env->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_EXIT, thread));
#endif
    }
#endif

#if LOG
    char inner_clinit_name[1024];
    get_class_name(jvmti_env, type, inner_clinit_name);
    cerr << inner_clinit_name << ".<clinit>() ENDED\n";
#endif
}





class Environment
{
    jvmtiEnv* env;

    static jvmtiIterationControl JNICALL heapObjectCallback(jlong class_tag, jlong size, jlong* tag_ptr, void* user_data)
    {
        ObjectContext* oc = *(ObjectContext**)tag_ptr;
        delete oc;
        return JVMTI_ITERATION_CONTINUE;
    }

public:
    Environment(jvmtiEnv* env) : env(env) {}

    ~Environment()
    {
        // Free ObjectContexts
        auto res = env->IterateOverHeap(JVMTI_HEAP_OBJECT_TAGGED, heapObjectCallback, nullptr);

        if(res != JVMTI_ERROR_NONE)
        {
            // May happen e.g. on normal process exit, when Destructor is called from c++ stdlib.
            return;
        }

        check(env->DisposeEnvironment());
    }

    jvmtiEnv* jvmti_env() const { return env; }
};

static shared_ptr<Environment> _jvmti_env_backing;
static weak_ptr<Environment> _jvmti_env;

static atomic<uint64_t> log_cnt;

static void increase_log_cnt()
{
    uint64_t cnt = ++log_cnt;

    if(cnt % 10000 == 0)
        cerr << "Log-count" << cnt << endl;
}


#include <unistd.h>
#include <link.h>
#include <sstream>

static int callback(dl_phdr_info* info, size_t size, void* data)
{
    auto name = string_view(info->dlpi_name);
    string_view self(AGENT_LIBRARY_NAME);

    if(name.ends_with(self))
    {
        *(string*)data = string_view(info->dlpi_name).substr(0, name.size() - self.size());
        return 1;
    }
    else
    {
        return 0;
    }
}

static string get_own_path()
{
    string path;
    bool success = dl_iterate_phdr(callback, &path);
    assert(success);
    return path;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved)
{
    //cerr << nounitbuf;
    //iostream::sync_with_stdio(false);

    jvmtiEnv* env;
    jint res = vm->GetEnv(reinterpret_cast<void **>(&env), JVMTI_VERSION_1_2);
    if(res)
        return 1;

    auto own_path = get_own_path();
    own_path.append("/" HOOK_JAR_NAME);
    check_code(1, env->AddToBootstrapClassLoaderSearch(own_path.c_str()));

    _jvmti_env_backing = std::make_shared<Environment>(env);
    _jvmti_env = _jvmti_env_backing;

    jvmtiCapabilities cap{ 0 };
    cap.can_generate_frame_pop_events = true;
    cap.can_tag_objects = true;
    cap.can_generate_object_free_events = true;
    cap.can_retransform_classes = true;
    cap.can_retransform_any_class = true;
    cap.can_generate_all_class_hook_events = true;
#if BREAKPOINTS_ENABLE
    cap.can_generate_breakpoint_events = true;
    cap.can_generate_field_modification_events = true;
#endif
#if LOG_METHOD_ENTRY_EXIT_EVENTS
    cap.can_generate_method_entry_events = true;
    cap.can_generate_method_exit_events = true;
#endif

    check_code(1, env->AddCapabilities(&cap));

    jvmtiEventCallbacks callbacks{ nullptr };
    callbacks.FieldModification = onFieldModification;
    callbacks.ClassPrepare = onClassPrepare;
    callbacks.VMInit = onVMInit;
    callbacks.FramePop = onFramePop;
    callbacks.ClassFileLoadHook = onClassFileLoad;
    callbacks.ThreadStart = onThreadStart;
    callbacks.ThreadEnd = onThreadEnd;
    callbacks.ObjectFree = onObjectFree;
#if LOG_METHOD_ENTRY_EXIT_EVENTS
    callbacks.MethodEntry = onMethodEntry;
    callbacks.MethodExit = onMethodExit;
#endif
    check_code(1, env->SetEventCallbacks(&callbacks, sizeof(callbacks)));
    check_code(1, env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, nullptr));
    check_code(1, env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_FRAME_POP, nullptr));
    check_code(1, env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_THREAD_START, nullptr));
    check_code(1, env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_THREAD_END, nullptr));
    check_code(1, env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_OBJECT_FREE, nullptr));
#if REWRITE_ENABLE
    check_code(1, env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr));
#endif

    return 0;
}

static void processClass(jvmtiEnv* jvmti_env, jclass klass)
{
    char* class_signature;
    char* class_generic;

    check(jvmti_env->GetClassSignature(klass, &class_signature, &class_generic));

#if LOG
    cerr << "New Class: " << class_signature << "\n";
#endif

    // Hook into field modification events

    jint field_count;
    jfieldID* fields;

    check(jvmti_env->GetClassFields(klass, &field_count, &fields));

    for(jint i = 0; i < field_count; i++)
    {
        char* field_name;
        char* field_signature;
        char* field_generic;

        check(jvmti_env->GetFieldName(klass, fields[i], &field_name, &field_signature, &field_generic));

        // Don't care for primitive types
        if(field_signature[0] != 'L' && field_signature[0] != '[')
            continue;

        auto return_code = jvmti_env->SetFieldModificationWatch(klass, fields[i]);
        if(return_code == JVMTI_ERROR_DUPLICATE)
            return; // Silently ignore if the class had already been processed
        check(return_code);

#if LOG
        cerr << "SetFieldModificationWatch: " << class_signature << " . " << field_name << " (" << field_signature << ")\n";
#endif
    }
}

static jniNativeInterface* original_jni;
static void get_class_name(jvmtiEnv *jvmti_env, jclass clazz, span<char> buffer);

static void logArrayWrite(JNIEnv* env, jobjectArray arr, jsize index, jobject val)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return;
    auto jvmti_env = jvmti_env_guard->jvmti_env();

    jthread thread;
    check(jvmti_env->GetCurrentThread(&thread));

    AgentThreadContext* tc = AgentThreadContext::from_thread(jvmti_env, thread);

    if(tc->clinit_empty())
        return;

    if(val)
    {
        ObjectContext* val_oc = ObjectContext::get_or_create(jvmti_env, env, val);
        if(!val_oc->allocReason)
            val_oc->allocReason = tc->clinit_top();
        ObjectContext* arr_oc = ObjectContext::get_or_create(jvmti_env, env, arr);
        if(!arr_oc->allocReason)
            arr_oc->allocReason = tc->clinit_top();
        ((ArrayObjectContext*)arr_oc)->registerWrite(index, val_oc, tc->clinit_top());
        increase_log_cnt();
    }

#if LOG || PRINT_CLINIT_HEAP_WRITES
    jclass arr_class = env->GetObjectClass(arr);

    char class_name[1024];
    get_class_name(jvmti_env, arr_class, class_name);

    char new_value_class_name[1024];
    if(!val)
    {
        strcpy(new_value_class_name, "null");
    }
    else
    {
        jclass new_value_class = env->GetObjectClass(val);
        get_class_name(jvmti_env, new_value_class, new_value_class_name);
    }

    char cause_class_name[1024];

    if(tc->clinit_empty())
        cause_class_name[0] = 0;
    else
        get_class_name(jvmti_env, tc->clinit_top(), cause_class_name);

    class_name[strlen(class_name) - 2] = 0; // Cut off last "[]"
    cerr << cause_class_name << ": " << class_name << '[' << index << ']' << " = " << new_value_class_name << '\n';
#endif
}

static void JNICALL setObjectArrayElement(JNIEnv *env, jobjectArray array, jsize index, jobject val)
{
    logArrayWrite(env, array, index, val);
    original_jni->SetObjectArrayElement(env, array, index, val);
}

static void JNICALL onVMInit(jvmtiEnv *jvmti_env, JNIEnv* jni_env, jthread thread)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return;

#if BREAKPOINTS_ENABLE
    check(jvmti_env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, nullptr));

    jint num_classes;
    jclass* classes_ptr;

    check(jvmti_env->GetLoadedClasses(&num_classes, &classes_ptr));

    span<jclass> classes(classes_ptr, num_classes);

    for(jclass clazz : classes)
    {
        jboolean is_modifiable;
        check(jvmti_env->IsModifiableClass(clazz, &is_modifiable));
        if(is_modifiable)
            check(jvmti_env->RetransformClasses(1, &clazz));

        jint status;
        check(jvmti_env->GetClassStatus(clazz, &status));
        if(status & JVMTI_CLASS_STATUS_PREPARED)
            processClass(jvmti_env, clazz);
    }
#endif // BREAKPOINTS_ENABLE



    jniNativeInterface* redirected_jni;
    check(jvmti_env->GetJNIFunctionTable(&original_jni));
    check(jvmti_env->GetJNIFunctionTable(&redirected_jni));
    redirected_jni->SetObjectArrayElement = setObjectArrayElement;
    check(jvmti_env->SetJNIFunctionTable(redirected_jni));
}

static void get_class_name(jvmtiEnv *jvmti_env, jclass clazz, span<char> buffer)
{
    if(!clazz)
    {
        buffer[0] = 0;
        return;
    }

    char* class_signature;
    char* class_generic;

    auto res = jvmti_env->GetClassSignature(clazz, &class_signature, &class_generic);

    if(res)
    {
        cerr << "GetClassSignature failed!" << endl;
        buffer[0] = 0;
        return;
    }

    size_t array_nesting = 0;
    while(class_signature[array_nesting] == '[')
        array_nesting++;

    size_t pos;

    if(class_signature[array_nesting] == 'L')
    {
        for(pos = 0; pos < buffer.size() - 1; pos++)
        {
            char c = class_signature[pos+array_nesting+1];

            if(c == 0 || c == ';')
            {
                break;
            }

            if(c == '/')
                c = '.';

            buffer[pos] = c;
        }

        if(pos >= buffer.size() - 1)
            buffer[buffer.size() - 1] = 0;
    }
    else
    {
        const char* keyword;

        switch(class_signature[array_nesting])
        {
            case 'B': keyword = "byte"; break;
            case 'C': keyword = "char"; break;
            case 'D': keyword = "double"; break;
            case 'F': keyword = "float"; break;
            case 'I': keyword = "int"; break;
            case 'J': keyword = "long"; break;
            case 'S': keyword = "short"; break;
            case 'Z': keyword = "boolean"; break;
            default:
                buffer[0] = 0;
                return;
        }

        for(pos = 0; keyword[pos]; pos++)
            buffer[pos] = keyword[pos];
    }

    for(size_t i = 0; i < array_nesting; i++)
    {
        buffer[pos++] = '[';
        buffer[pos++] = ']';
    }

    buffer[pos] = 0;
}

static void onFieldModification(
        jvmtiEnv *jvmti_env,
        JNIEnv* jni_env,
        jthread thread,
        jmethodID method,
        jlocation location,
        jclass field_klass,
        jobject object,
        jfieldID field,
        char signature_type,
        jvalue new_value)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return;

    char* field_name;
    char* field_signature;
    char* field_generic;
    check(jvmti_env->GetFieldName(field_klass, field, &field_name, &field_signature, &field_generic));

    if(!new_value.l)
        return;

    AgentThreadContext* tc = AgentThreadContext::from_thread(jvmti_env, thread);

    assert(!tc->clinit_empty());

    if(!tc->clinit_empty())
    {
        ObjectContext* val_oc = ObjectContext::get_or_create(jvmti_env, jni_env, new_value.l);
        if(!val_oc->allocReason)
            val_oc->allocReason = tc->clinit_top();

        if(object)
        {
            auto object_oc = (NonArrayObjectContext*)ObjectContext::get_or_create(jvmti_env, jni_env, object);
            if(!object_oc->allocReason)
                object_oc->allocReason = tc->clinit_top();
            object_oc->registerWrite(field, val_oc, tc->clinit_top());
            increase_log_cnt();
        }
        else
        {
            ClassContext* cc = ClassContext::get_or_create(jvmti_env, jni_env, field_klass);
            cc->registerStaticWrite(jvmti_env, jni_env, field, val_oc, tc->clinit_top());
            increase_log_cnt();
        }
    }

#if LOG || PRINT_CLINIT_HEAP_WRITES
    char class_name[1024];
    get_class_name(jvmti_env, field_klass, {class_name, class_name + 1024});

    char new_value_class_name[1024];
    jclass new_value_class = jni_env->GetObjectClass(new_value.l);
    get_class_name(jvmti_env, new_value_class, new_value_class_name);

    char cause_class_name[1024];

    if(tc->clinit_empty())
        cause_class_name[0] = 0;
    else
        get_class_name(jvmti_env, tc->clinit_top(), cause_class_name);

    if(string_view(new_value_class_name) == "java.lang.String")
    {
        const char* str_val = jni_env->GetStringUTFChars((jstring)new_value.l, nullptr);
        cerr << cause_class_name << ": " << class_name << "." << field_name << " = \"" << str_val << "\"\n";
        jni_env->ReleaseStringUTFChars((jstring)new_value.l, str_val);
    }
    else if(string_view(new_value_class_name) == "java.lang.Class")
    {
        char val_content[1024];
        get_class_name(jvmti_env, (jclass)new_value.l, val_content);
        cerr << cause_class_name << ": " << class_name << "." << field_name << " = java.lang.Class: \"" << val_content << "\"\n";
    }
    else
    {
        cerr << cause_class_name << ": " << class_name << "." << field_name << " = " << new_value_class_name << '\n';
    }
#endif
}

static void JNICALL onFramePop(
        jvmtiEnv *jvmti_env,
        JNIEnv* jni_env,
        jthread thread,
        jmethodID method,
        jboolean was_popped_by_exception)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return;

    jclass type;
    check(jvmti_env->GetMethodDeclaringClass(method, &type));

    removeFromTracingStack(jvmti_env, jni_env, thread, type);
}

static void JNICALL onClassPrepare(
        jvmtiEnv *jvmti_env,
        JNIEnv* jni_env,
        jthread thread,
        jclass klass)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return;

    processClass(jvmti_env, klass);
}

static void JNICALL onClassFileLoad(
        jvmtiEnv *jvmti_env,
        JNIEnv* jni_env,
        jclass class_being_redefined,
        jobject loader,
        const char* name,
        jobject protection_domain,
        jint class_data_len,
        const unsigned char* class_data,
        jint* new_class_data_len,
        unsigned char** new_class_data)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return;

#if LOG
    cerr << "ClassLoad: " << name << endl;
#endif

    if(string_view(name) == HOOK_CLASS_NAME // Do not replace our own hooks, logically
    || string_view(name) == "com/oracle/svm/core/jni/functions/JNIFunctionTables") // Crashes during late compile phase
        return;

    add_clinit_hook(jvmti_env, class_data, class_data_len, new_class_data, new_class_data_len);
}

extern "C" JNIEXPORT void JNICALL Java_HeapAssignmentTracingHooks_onClinitStart(JNIEnv* env, jobject self)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return;
    auto jvmti_env = jvmti_env_guard->jvmti_env();

    jvmtiPhase phase;
    check(jvmti_env->GetPhase(&phase));

    if(phase != JVMTI_PHASE_LIVE)
        return;

    jthread thread;
    check(jvmti_env->GetCurrentThread(&thread));

    jmethodID method;
    jlocation location;
    check(jvmti_env->GetFrameLocation(thread, 1, &method, &location));

    jclass type;
    check(jvmti_env->GetMethodDeclaringClass(method, &type));

    addToTracingStack(jvmti_env, env, thread, type);

    check(jvmti_env->NotifyFramePop(thread, 1));
}

static void JNICALL onThreadStart(jvmtiEnv *jvmti_env, JNIEnv* jni_env, jthread thread)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return;

    auto* tc = new AgentThreadContext();
    check(jvmti_env->SetThreadLocalStorage(thread, tc));
}

static void JNICALL onThreadEnd(jvmtiEnv *jvmti_env, JNIEnv* jni_env, jthread thread)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return;

    AgentThreadContext* tc = AgentThreadContext::from_thread(jvmti_env, thread);
    delete tc;
}

void onObjectFree(jvmtiEnv *jvmti_env, jlong tag)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return;

#if LOG
    cerr << "Object freed!\n";
#endif
    auto* oc = (ObjectContext*)tag;

    assert(!dynamic_cast<ClassContext*>(oc));

    delete oc;
}

extern "C" JNIEXPORT void JNICALL Java_HeapAssignmentTracingHooks_notifyArrayWrite(JNIEnv* env, jobject self, jobjectArray arr, jint index, jobject val)
{
    logArrayWrite(env, arr, index, val);
}

extern "C" JNIEXPORT void JNICALL Java_HeapAssignmentTracingHooks_onThreadStart(JNIEnv* env, jobject self, jthread newThread)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return;
    auto jvmti_env = jvmti_env_guard->jvmti_env();

#if LOG || PRINT_CLINIT_HEAP_WRITES
    jvmtiPhase phase;
    check(jvmti_env->GetPhase(&phase));

    if(phase != JVMTI_PHASE_LIVE)
        return;

    jthread thread;
    check(jvmti_env->GetCurrentThread(&thread));

    AgentThreadContext* tc = AgentThreadContext::from_thread(jvmti_env, thread);

    if(tc->clinit_empty())
        return;

    char outer_clinit_name[1024];
    get_class_name(jvmti_env, tc->clinit_top(), outer_clinit_name);

    jvmtiThreadInfo info;
    check(jvmti_env->GetThreadInfo(newThread, &info));

    cerr << outer_clinit_name << ": " << "Thread.start(): \"" << info.name << "\"\n";
#endif
}

extern "C" JNIEXPORT jobject JNICALL Java_com_oracle_graal_pointsto_reports_HeapAssignmentTracing_00024NativeImpl_getResponsibleClass(JNIEnv* env, jobject thisClass, jobject imageHeapObject)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return nullptr;
    auto jvmti_env = jvmti_env_guard->jvmti_env();

    ObjectContext* oc = ObjectContext::get(jvmti_env, imageHeapObject);

    if(!oc)
        return nullptr;

    return oc->allocReason;
}

extern "C" JNIEXPORT jobject JNICALL Java_com_oracle_graal_pointsto_reports_HeapAssignmentTracing_00024NativeImpl_getClassResponsibleForNonstaticFieldWrite(JNIEnv* env, jobject thisClass, jobject receiver, jobject field, jobject val)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    auto jvmti_env = jvmti_env_guard->jvmti_env();

    auto receiver_oc = (NonArrayObjectContext*)ObjectContext::get(jvmti_env, receiver);
    auto val_oc = (NonArrayObjectContext*)ObjectContext::get(jvmti_env, val);

    jfieldID fieldID = env->FromReflectedField(field);

    char *name, *signature, *generic;
    jvmtiError error = jvmti_env->GetFieldName(env->GetObjectClass(receiver), fieldID, &name, &signature, &generic);

    if(error == JVMTI_ERROR_INVALID_FIELDID)
    {
        // May happen when the field of a substitution is accessed
#ifdef SHOW_EXISTING
#if SHOW_EXISTING == 0
        cerr << "Invalid Field!\n";
#endif
#endif
        return nullptr;
    }

    check(error);

    jobject res = nullptr;

    if(receiver_oc && val_oc)
    {
        res = receiver_oc->getWriteReason(env->FromReflectedField(field), val_oc);
    }

#ifdef SHOW_EXISTING
    if(bool(res) == SHOW_EXISTING)
    {
        char receiver_class_name[1024];
        get_class_name(jvmti_env, env->GetObjectClass(receiver), receiver_class_name);
        char val_class_name[1024];
        get_class_name(jvmti_env, env->GetObjectClass(val), val_class_name);

        stringstream s;
        s << receiver_class_name << '.' << name << '=' << val_class_name << endl;
        cerr << s.str();
    }
#endif

    return res;
}

extern "C" JNIEXPORT jobject JNICALL Java_com_oracle_graal_pointsto_reports_HeapAssignmentTracing_00024NativeImpl_getClassResponsibleForStaticFieldWrite(JNIEnv* env, jobject thisClass, jclass declaring, jobject field, jobject val)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return nullptr;
    auto jvmti_env = jvmti_env_guard->jvmti_env();

    auto declaring_cc = ClassContext::get_or_create(jvmti_env, env, declaring);
    auto val_oc = (NonArrayObjectContext*)ObjectContext::get(jvmti_env, val);

    jint class_status;
    check(jvmti_env->GetClassStatus(declaring, &class_status));

    if(!(class_status & JVMTI_CLASS_STATUS_INITIALIZED))
    {
        char class_name[1024];
        get_class_name(jvmti_env, declaring, class_name);
        cerr << "Class not initialized yet field being asked for: " << class_name << endl;
        return nullptr;
    }

    jfieldID fieldID = env->FromReflectedField(field);

    jobject res = nullptr;

    if(val_oc)
    {
        res = declaring_cc->getStaticFieldReason(jvmti_env, env, env->FromReflectedField(field), val_oc);
    }

#ifdef SHOW_EXISTING
    char declaring_class_name[1024];
    get_class_name(jvmti_env, declaring, declaring_class_name);
    char val_class_name[1024];
    get_class_name(jvmti_env, env->GetObjectClass(val), val_class_name);
    char *field_name, *signature, *generic;
    check(jvmti_env->GetFieldName(declaring, fieldID, &field_name, &signature, &generic));

    if(bool(res) == SHOW_EXISTING)
    {
        stringstream s;
        s << declaring_class_name << '.' << field_name << '=' << val_class_name << endl;
        cerr << s.str();
    }
#endif

    return res;
}

extern "C" JNIEXPORT jobject JNICALL Java_com_oracle_graal_pointsto_reports_HeapAssignmentTracing_00024NativeImpl_getClassResponsibleForArrayWrite(JNIEnv* env, jobject thisClass, jobjectArray array, jint index, jobject val)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return nullptr;
    auto jvmti_env = jvmti_env_guard->jvmti_env();

    auto array_oc = (ArrayObjectContext*)ObjectContext::get(jvmti_env, array);
    auto val_oc = (NonArrayObjectContext*)ObjectContext::get(jvmti_env, val);

    jobject res = nullptr;

    if(array_oc && val_oc)
    {
        res = array_oc->getWriteReason(index, val_oc);
    }

#ifdef SHOW_EXISTING
    if(bool(res) == SHOW_EXISTING)
    {
        char array_class_name[1024];
        get_class_name(jvmti_env, env->GetObjectClass(array), array_class_name);
        char val_class_name[1024];
        get_class_name(jvmti_env, env->GetObjectClass(val), val_class_name);

        stringstream s;
        s << array_class_name << '=' << val_class_name << endl;
        cerr << s.str();
    }
#endif

    return res;
}

extern "C" JNIEXPORT jobject JNICALL Java_com_oracle_graal_pointsto_reports_HeapAssignmentTracing_00024NativeImpl_getBuildTimeClinitResponsibleForBuildTimeClinit(JNIEnv* env, jobject thisClass, jclass clazz)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return nullptr;
    auto jvmti_env = jvmti_env_guard->jvmti_env();

    ClassContext* cc = ClassContext::get_or_create(jvmti_env, env, clazz);
    return cc->made_reachable_by;
}

extern "C" JNIEXPORT void JNICALL Java_com_oracle_graal_pointsto_reports_HeapAssignmentTracing_00024NativeImpl_beginTracing(JNIEnv* env, jobject thisClass, jobject customReason)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return;
    auto jvmti_env = jvmti_env_guard->jvmti_env();

    jthread thread;
    check(jvmti_env->GetCurrentThread(&thread));

    addToTracingStack(jvmti_env, env, thread, customReason);
}

extern "C" JNIEXPORT void JNICALL Java_com_oracle_graal_pointsto_reports_HeapAssignmentTracing_00024NativeImpl_endTracing(JNIEnv* env, jobject thisClass, jobject customReason)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return;
    auto jvmti_env = jvmti_env_guard->jvmti_env();

    jthread thread;
    check(jvmti_env->GetCurrentThread(&thread));

    removeFromTracingStack(jvmti_env, env, thread, customReason);
}

extern "C" JNIEXPORT void JNICALL Java_com_oracle_graal_pointsto_reports_HeapAssignmentTracing_00024NativeImpl_dispose(JNIEnv* env, jobject thisClass)
{
    _jvmti_env_backing.reset();
}


#if LOG_METHOD_ENTRY_EXIT_EVENTS
static void JNICALL onMethodEntry(jvmtiEnv *jvmti_env, JNIEnv* jni_env, jthread thread, jmethodID method)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return;

    AgentThreadContext* tc = AgentThreadContext::from_thread(jvmti_env, thread);

    if(!tc || tc->clinit_empty())
        return;

    char outer_clinit_name[1024];
    get_class_name(jvmti_env, tc->clinit_top(), outer_clinit_name);

    char declaringClassName[1024];

    jclass declaringClass;
    check(jvmti_env->GetMethodDeclaringClass(method, &declaringClass));
    get_class_name(jvmti_env, declaringClass, declaringClassName);

    char *name, *signature, *generic;
    check(jvmti_env->GetMethodName(method, &name, &signature, &generic));

    cerr << outer_clinit_name << ": Entering " << declaringClassName << '.' << name << '(' << signature << ")\n";
}

static void JNICALL onMethodExit(jvmtiEnv *jvmti_env, JNIEnv* jni_env, jthread thread, jmethodID method, jboolean was_popped_by_exception, jvalue return_value)
{
    auto jvmti_env_guard = _jvmti_env.lock();
    if(!jvmti_env_guard)
        return;

    AgentThreadContext* tc = AgentThreadContext::from_thread(jvmti_env, thread);

    if(!tc || tc->clinit_empty())
        return;

    char outer_clinit_name[1024];
    get_class_name(jvmti_env, tc->clinit_top(), outer_clinit_name);

    char declaringClassName[1024];

    jclass declaringClass;
    check(jvmti_env->GetMethodDeclaringClass(method, &declaringClass));
    get_class_name(jvmti_env, declaringClass, declaringClassName);

    char *name, *signature, *generic;
    check(jvmti_env->GetMethodName(method, &name, &signature, &generic));

    cerr << outer_clinit_name << ": Exiting " << declaringClassName << '.' << name << '(' << signature << ")\n";
}
#endif