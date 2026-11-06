use core::ffi::c_void;

/// Stable version for the first Kotlin/JNI bridge contract.
#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_com_termux_app_RustCoreBridge_apiVersion(
    _env: *mut c_void,
    _class: *mut c_void,
) -> i32 {
    1
}
