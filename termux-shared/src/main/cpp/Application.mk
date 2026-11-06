APP_STL := c++_static
APP_CPPFLAGS := -Oz -fvisibility=hidden -ffunction-sections -fdata-sections -fno-exceptions -fno-rtti
APP_LDFLAGS := -Wl,--gc-sections -Wl,--exclude-libs,ALL

