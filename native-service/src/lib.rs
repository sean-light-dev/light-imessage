//! Native library entry point packaged in the tool APK.

use std::hint::black_box;

/// Returns the native service ABI version expected by the Kotlin launcher.
///
/// Referencing the rustpush type from the exported entry point ensures the
/// packaged library links the Rust push core rather than being an empty stub.
#[no_mangle]
pub extern "C" fn rustpush_service_abi_version() -> u32 {
    black_box(std::mem::size_of::<rustpush::RegisterMeta>());
    1
}
