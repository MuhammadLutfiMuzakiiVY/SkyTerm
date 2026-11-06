package com.termux.app

/** Narrow JNI surface for the Rust core. Keep Android framework objects outside this boundary. */
internal object RustCoreBridge {
    private const val EXPECTED_API_VERSION = 1

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var failureMessage: String? = null

    init {
        try {
            System.loadLibrary("termux_core")
            libraryLoaded = true
        } catch (error: LinkageError) {
            failureMessage = error.message ?: error.javaClass.simpleName
        } catch (error: SecurityException) {
            failureMessage = error.message ?: error.javaClass.simpleName
        }
    }

    @JvmStatic external fun apiVersion(): Int

    /** Return zero when the native library is missing or its JNI contract is incompatible. */
    @JvmStatic
    fun apiVersionOrZero(): Int {
        if (!libraryLoaded) return 0

        return try {
            val version = apiVersion()
            if (version == EXPECTED_API_VERSION) {
                version
            } else {
                libraryLoaded = false
                failureMessage = "Unsupported Rust JNI API version: $version"
                0
            }
        } catch (error: LinkageError) {
            libraryLoaded = false
            failureMessage = error.message ?: error.javaClass.simpleName
            0
        }
    }

    @JvmStatic
    fun getFailureMessage(): String? = failureMessage
}
