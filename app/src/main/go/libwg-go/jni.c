#include <jni.h>
#include <stdlib.h>
#include <string.h>
struct go_string { const char *str; long n; };
extern int wgTurnOn(struct go_string ifname, int tun_fd, struct go_string settings);
extern void wgTurnOff(int handle);
extern int wgGetSocketV4(int handle);
extern int wgGetSocketV6(int handle);
extern char *wgGetConfig(int handle);
extern char *wgVersion();
JNIEXPORT jint JNICALL Java_com_wireguard_android_backend_GoBackend_wgTurnOn(JNIEnv *env, jclass c, jstring ifname, jint tun_fd, jstring settings) {
 const char *a=(*env)->GetStringUTFChars(env,ifname,0); size_t an=(*env)->GetStringUTFLength(env,ifname);
 const char *b=(*env)->GetStringUTFChars(env,settings,0); size_t bn=(*env)->GetStringUTFLength(env,settings);
 int r=wgTurnOn((struct go_string){.str=a,.n=an},tun_fd,(struct go_string){.str=b,.n=bn});
 (*env)->ReleaseStringUTFChars(env,ifname,a); (*env)->ReleaseStringUTFChars(env,settings,b); return r;
}
JNIEXPORT void JNICALL Java_com_wireguard_android_backend_GoBackend_wgTurnOff(JNIEnv *e,jclass c,jint h){wgTurnOff(h);}
JNIEXPORT jint JNICALL Java_com_wireguard_android_backend_GoBackend_wgGetSocketV4(JNIEnv *e,jclass c,jint h){return wgGetSocketV4(h);}
JNIEXPORT jint JNICALL Java_com_wireguard_android_backend_GoBackend_wgGetSocketV6(JNIEnv *e,jclass c,jint h){return wgGetSocketV6(h);}
JNIEXPORT jstring JNICALL Java_com_wireguard_android_backend_GoBackend_wgGetConfig(JNIEnv *e,jclass c,jint h){char *s=wgGetConfig(h); if(!s)return NULL; jstring r=(*e)->NewStringUTF(e,s); free(s); return r;}
JNIEXPORT jstring JNICALL Java_com_wireguard_android_backend_GoBackend_wgVersion(JNIEnv *e,jclass c){char *s=wgVersion(); if(!s)return NULL; jstring r=(*e)->NewStringUTF(e,s); free(s); return r;}
