#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#if defined(_MSC_VER)
#include <intrin.h>
#elif !defined(__cplusplus)
#include <stdatomic.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int32_t code;
} FfiStatus;

#ifdef __cplusplus
static inline FfiStatus boltffi_status_from_code(int32_t code) {
    FfiStatus status = { code };
    return status;
}
#define FFI_STATUS_OK boltffi_status_from_code(0)
#define FFI_STATUS_NULL_POINTER boltffi_status_from_code(1)
#define FFI_STATUS_BUFFER_TOO_SMALL boltffi_status_from_code(2)
#define FFI_STATUS_INVALID_ARG boltffi_status_from_code(3)
#define FFI_STATUS_CANCELLED boltffi_status_from_code(4)
#define FFI_STATUS_INTERNAL_ERROR boltffi_status_from_code(100)
#else
#define FFI_STATUS_OK ((FfiStatus){0})
#define FFI_STATUS_NULL_POINTER ((FfiStatus){1})
#define FFI_STATUS_BUFFER_TOO_SMALL ((FfiStatus){2})
#define FFI_STATUS_INVALID_ARG ((FfiStatus){3})
#define FFI_STATUS_CANCELLED ((FfiStatus){4})
#define FFI_STATUS_INTERNAL_ERROR ((FfiStatus){100})
#endif

typedef struct {
    uint8_t *ptr;
    uintptr_t len;
    uintptr_t cap;
    uintptr_t align;
} FfiBuf_u8;

typedef struct {
    uint8_t *ptr;
    uintptr_t len;
    uintptr_t cap;
} FfiString;

typedef struct {
    FfiString message;
} FfiError;

typedef struct {
    const uint8_t *ptr;
    uintptr_t len;
} FfiSpan;

typedef const void *RustFutureHandle;
typedef int8_t StreamPollResult;
typedef int32_t WaitResult;
typedef void (*RustFutureContinuationCallback)(uint64_t callback_data, int8_t poll_result);
typedef void (*StreamContinuationCallback)(uint64_t callback_data, StreamPollResult result);

#if defined(_MSC_VER)
static inline bool boltffi_atomic_u8_cas(uint8_t *state, uint8_t expected, uint8_t desired) {
    return _InterlockedCompareExchange8((volatile char *)state, (char)desired, (char)expected) == (char)expected;
}

static inline uint64_t boltffi_atomic_u64_exchange(uint64_t *slot, uint64_t value) {
    return (uint64_t)_InterlockedExchange64((volatile __int64 *)slot, (__int64)value);
}

static inline bool boltffi_atomic_u64_cas(uint64_t *slot, uint64_t expected, uint64_t desired) {
    return (uint64_t)_InterlockedCompareExchange64((volatile __int64 *)slot, (__int64)desired, (__int64)expected) == expected;
}

static inline uint64_t boltffi_atomic_u64_load(uint64_t *slot) {
    return (uint64_t)_InterlockedCompareExchange64((volatile __int64 *)slot, 0, 0);
}
#elif defined(__cplusplus) && (defined(__clang__) || defined(__GNUC__))
static inline bool boltffi_atomic_u8_cas(uint8_t *state, uint8_t expected, uint8_t desired) {
    return __atomic_compare_exchange_n(state, &expected, desired, false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
}

static inline uint64_t boltffi_atomic_u64_exchange(uint64_t *slot, uint64_t value) {
    return __atomic_exchange_n(slot, value, __ATOMIC_ACQ_REL);
}

static inline bool boltffi_atomic_u64_cas(uint64_t *slot, uint64_t expected, uint64_t desired) {
    return __atomic_compare_exchange_n(slot, &expected, desired, false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
}

static inline uint64_t boltffi_atomic_u64_load(uint64_t *slot) {
    return __atomic_load_n(slot, __ATOMIC_ACQUIRE);
}
#elif defined(__cplusplus)
#error "BoltFFI C++ atomics require GCC, Clang, or MSVC"
#else
static inline bool boltffi_atomic_u8_cas(uint8_t *state, uint8_t expected, uint8_t desired) {
    return atomic_compare_exchange_strong_explicit((_Atomic uint8_t *)state, &expected, desired, memory_order_acq_rel, memory_order_acquire);
}

static inline uint64_t boltffi_atomic_u64_exchange(uint64_t *slot, uint64_t value) {
    return atomic_exchange_explicit((_Atomic uint64_t *)slot, value, memory_order_acq_rel);
}

static inline bool boltffi_atomic_u64_cas(uint64_t *slot, uint64_t expected, uint64_t desired) {
    return atomic_compare_exchange_strong_explicit((_Atomic uint64_t *)slot, &expected, desired, memory_order_acq_rel, memory_order_acquire);
}

static inline uint64_t boltffi_atomic_u64_load(uint64_t *slot) {
    return atomic_load_explicit((_Atomic uint64_t *)slot, memory_order_acquire);
}
#endif

typedef struct {
    uint64_t handle;
    const void *vtable;
} BoltFFICallbackHandle;

void satora_boltffi_free_string(FfiString string);
void satora_boltffi_free_buf(FfiBuf_u8 buf);
FfiString satora_boltffi_buf_into_string(FfiBuf_u8 buf);
FfiBuf_u8 satora_boltffi_buf_from_bytes(const uint8_t *ptr, uintptr_t len);
FfiBuf_u8 satora_boltffi_buf_with_len(uintptr_t len);
FfiBuf_u8 satora_boltffi_callback_error(const uint8_t *message, uintptr_t length);
FfiStatus satora_boltffi_last_error_message(FfiString *out);
void satora_boltffi_clear_last_error(void);
typedef int32_t ___SatoraErrorCode;
#define SATORA_ERROR_CODE_OTHER ((___SatoraErrorCode)0)
#define SATORA_ERROR_CODE_NETWORK ((___SatoraErrorCode)1)
#define SATORA_ERROR_CODE_API ((___SatoraErrorCode)2)
#define SATORA_ERROR_CODE_STORAGE ((___SatoraErrorCode)3)
#define SATORA_ERROR_CODE_INVALID_INPUT ((___SatoraErrorCode)4)
#define SATORA_ERROR_CODE_TIMEOUT ((___SatoraErrorCode)5)
#define SATORA_ERROR_CODE_NOT_FOUND ((___SatoraErrorCode)6)
#define SATORA_ERROR_CODE_CLAIM_IN_FLIGHT ((___SatoraErrorCode)7)
#define SATORA_ERROR_CODE_UNKNOWN_CONTRACT ((___SatoraErrorCode)8)
void boltffi_release_class_satora_ffi_satora_client(uint64_t handle);
FfiBuf_u8 boltffi_init_class_satora_ffi_satora_client_open(const uint8_t *data_dir_ptr, uintptr_t data_dir_len, const uint8_t *mnemonic_ptr, uintptr_t mnemonic_len, uint64_t *return_out);
RustFutureHandle boltffi_method_class_satora_ffi_satora_client_quote(uint64_t receiver, uint64_t sats);
int8_t boltffi_async_method_class_satora_ffi_satora_client_quote_poll(RustFutureHandle handle, uint64_t callback_data, void (*callback)(uint64_t, int8_t));
FfiBuf_u8 boltffi_async_method_class_satora_ffi_satora_client_quote_complete(RustFutureHandle handle, FfiStatus *out_status, FfiBuf_u8 *return_out);
FfiBuf_u8 boltffi_async_method_class_satora_ffi_satora_client_quote_panic_message(RustFutureHandle handle);
void boltffi_async_method_class_satora_ffi_satora_client_quote_cancel(RustFutureHandle handle);
void boltffi_async_method_class_satora_ffi_satora_client_quote_free(RustFutureHandle handle);
RustFutureHandle boltffi_method_class_satora_ffi_satora_client_create_swap(uint64_t receiver, uint64_t sats, const uint8_t *destination_ptr, uintptr_t destination_len);
int8_t boltffi_async_method_class_satora_ffi_satora_client_create_swap_poll(RustFutureHandle handle, uint64_t callback_data, void (*callback)(uint64_t, int8_t));
FfiBuf_u8 boltffi_async_method_class_satora_ffi_satora_client_create_swap_complete(RustFutureHandle handle, FfiStatus *out_status, FfiBuf_u8 *return_out);
FfiBuf_u8 boltffi_async_method_class_satora_ffi_satora_client_create_swap_panic_message(RustFutureHandle handle);
void boltffi_async_method_class_satora_ffi_satora_client_create_swap_cancel(RustFutureHandle handle);
void boltffi_async_method_class_satora_ffi_satora_client_create_swap_free(RustFutureHandle handle);
FfiBuf_u8 boltffi_method_class_satora_ffi_satora_client_swap(uint64_t receiver, const uint8_t *id_ptr, uintptr_t id_len, FfiBuf_u8 *return_out);
RustFutureHandle boltffi_method_class_satora_ffi_satora_client_refresh_swap(uint64_t receiver, const uint8_t *id_ptr, uintptr_t id_len);
int8_t boltffi_async_method_class_satora_ffi_satora_client_refresh_swap_poll(RustFutureHandle handle, uint64_t callback_data, void (*callback)(uint64_t, int8_t));
FfiBuf_u8 boltffi_async_method_class_satora_ffi_satora_client_refresh_swap_complete(RustFutureHandle handle, FfiStatus *out_status, FfiBuf_u8 *return_out);
FfiBuf_u8 boltffi_async_method_class_satora_ffi_satora_client_refresh_swap_panic_message(RustFutureHandle handle);
void boltffi_async_method_class_satora_ffi_satora_client_refresh_swap_cancel(RustFutureHandle handle);
void boltffi_async_method_class_satora_ffi_satora_client_refresh_swap_free(RustFutureHandle handle);
FfiBuf_u8 boltffi_method_class_satora_ffi_satora_client_mnemonic(uint64_t receiver, FfiBuf_u8 *return_out);
FfiBuf_u8 boltffi_method_class_satora_ffi_satora_client_list_swaps(uint64_t receiver, FfiBuf_u8 *return_out);
RustFutureHandle boltffi_method_class_satora_ffi_satora_client_claim(uint64_t receiver, const uint8_t *id_ptr, uintptr_t id_len);
int8_t boltffi_async_method_class_satora_ffi_satora_client_claim_poll(RustFutureHandle handle, uint64_t callback_data, void (*callback)(uint64_t, int8_t));
FfiBuf_u8 boltffi_async_method_class_satora_ffi_satora_client_claim_complete(RustFutureHandle handle, FfiStatus *out_status, FfiBuf_u8 *return_out);
FfiBuf_u8 boltffi_async_method_class_satora_ffi_satora_client_claim_panic_message(RustFutureHandle handle);
void boltffi_async_method_class_satora_ffi_satora_client_claim_cancel(RustFutureHandle handle);
void boltffi_async_method_class_satora_ffi_satora_client_claim_free(RustFutureHandle handle);

#ifdef __cplusplus
}
#endif