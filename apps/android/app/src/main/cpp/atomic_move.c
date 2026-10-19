#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/fs.h>
#include <sys/syscall.h>
#include <unistd.h>

// Os.rename replaces existing files. Hard links are denied by Android's app sandbox.
// Use the kernel's exclusive rename; never fall back to check-then-rename.
JNIEXPORT jint JNICALL
Java_com_noahwallet_services_backup_AtomicBackupMove_moveExclusive(
    JNIEnv *env, jobject self, jbyteArray source, jbyteArray destination) {
    (void)self;
    jbyte *from = (*env)->GetByteArrayElements(env, source, NULL);
    if (!from) return ENOMEM;
    jbyte *to = (*env)->GetByteArrayElements(env, destination, NULL);
    if (!to) {
        (*env)->ReleaseByteArrayElements(env, source, from, JNI_ABORT);
        return ENOMEM;
    }
    int result = syscall(__NR_renameat2, AT_FDCWD, (char *)from,
                         AT_FDCWD, (char *)to, RENAME_NOREPLACE);
    int error = result == 0 ? 0 : errno;
    (*env)->ReleaseByteArrayElements(env, destination, to, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, source, from, JNI_ABORT);
    return error;
}
