cmake_minimum_required(VERSION 3.22)
project(irix_time_jni C)

# 用链接器 soname 而不是绝对路径，否则 APK 里的
# libirix_time_jni.so 会 NEEDED "D:/.../libxmc_time.so"，手机加载必失败。
add_library(irix_time_jni SHARED time_jni.c)
target_link_directories(irix_time_jni PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
target_link_options(irix_time_jni PRIVATE
  "LINKER:--no-as-needed"
  "LINKER:-l:libxmc_time.so")
target_link_libraries(irix_time_jni log)
