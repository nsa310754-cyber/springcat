#include <jni.h>
#include <string>

// This function is the C++ side of the app. Kotlin calls it through JNI and
// shows whatever string it returns. All the real logic lives here in C++.
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_springcat_MainActivity_stringFromCpp(JNIEnv *env, jobject /* this */) {
    std::string message = "Hello from C++ 🐱  (native-lib.cpp)";
    return env->NewStringUTF(message.c_str());
}
