 
## 0.26.1

* disabled checkstyle and ktlint in build.gradle
* updated most existing dependencies
* all java code converted to Kotlin
* resolved some issues and ensured null safety
* added the following to the ShapeableImageView to stop the logcat errors: app:strokeColor="@null"
* built using SDK 34 and target to SDK 33

### Issues

* no such issue on my S21 Android 14 device, but on my Android 9 device:
	* if auto play is set, play from related items only plays audio; unset auto play makes the video to play normally.

* if build target to SDK 34, there appear various issues on my S21 Android 14 device

* sometimes when starting playing a video, this is the popup at the bottom saying "something went wrong" though things appear normal.  But tapping on "Report" in the popup, crashes the app.  Logcat reports a runtime exception of:
```
ACRA caught a RuntimeException for org.schabi.newpipe.debug.main                                                                                                  java.lang.RuntimeException: Failure from system
Caused by: android.os.TransactionTooLargeException: data parcel size 1257884 bytes                                                                                                at android.os.BinderProxy.transactNative(Native Method)
```
## 0.26.2

* introductory migration to androidx.media3: removed exoplayer2 et al, commented out functions related to MediaSessionConnector etc.
* though not thoroughly tested, app appears working

## 0.26.3

* fixed crash bug when doing download

## 0.26.4

* app built to target to SDK 34
* set bottomsheet to collapsed when video view is closed, rather than hidden.

### Issues

The reason to set it to collapsed is this: on my S21 Android 14 device, bottomSheetBehavior!!.setState(BottomSheetBehavior.STATE_EXPANDED) doesn't expand the bottomsheet, whether it was hidden or collapsed.  Originally, the bottomsheet was set to hidden, so when starting a new video from the list, the view is still invisible, making it appear like nothing is happening.  

Now it's set to collapsed, so when a new video is starting, on S21 Android14, the user need to manually expand the bottomsheet.

On my Android 9 device however, expand on play is automatic.

## 0.26.5

* various bug fixes
* further media3 tuning
* some services like download, feedload, fetcher are now started as background services

### Issues

Download process runs through but gets invalid mp4 files

The "something went wrong" popups don't seem to indicate anything important.  Printed a stacktrace when it happens, basically:
```
 org.schabi.newpipe.extractor.exceptions.ParsingException: Could not get comment id
 org.schabi.newpipe.extractor.exceptions.ParsingException: Could not get comment text
 org.schabi.newpipe.extractor.exceptions.ParsingException: Could not get author thumbnails
 org.schabi.newpipe.extractor.exceptions.ParsingException: Could not get publishedTimeText
```	

Noticed these Logcat messages on my S21 Android 14
```
BufferQueueProducer     org.schabi.newpipex.debug.main       W  [SurfaceView[org.schabi.newpipex.debug.main/org.schabi.newpipe.MainActivity]@0#1(BLAST Consumer)1](id:195c00000001,api:3,p:6492,c:6492) detachBuffer: slot 41 is not owned by the producer (state = FREE)
BufferQueueProducer     org.schabi.newpipex.debug.main       W  [SurfaceTexture-1-6492-0](id:195c00000002,api:3,p:6492,c:6492) detachBuffer: slot 50 is not owned by the producer (state = FREE)
BufferQueueProducer     org.schabi.newpipex.debug.main       W  [MediaCodec.release](id:195c00000003,api:3,p:6492,c:6492) detachBuffer: slot 42 is not owned by the producer (state = FREE)
```
