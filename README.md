This project is a full conversion of [NewPipe](https://github.com/TeamNewPipe/NewPipe) (cloned as of Feb 5 2024) to Kotlin.  Most existing dependencies are updated.  ~Built SDK is 34 and target SDK is 33.~  Code and null safety issues are resolved after the conversion.  ~Other than that, there is no logic or structural change from the original project.~

Introductory migration to androidx.media3 and removal of exoplayer2.  Though not having been thoroughly tested, things appear to work.

Built SDK is 34 and target SDK is 34

For non-intrusive testing, the appId and name is changed to NewPipeX.

See more changes and issues in [changelog](changelog.md)

## Copyright

New files and contents in the project are copyrighted in 2024 by Xilin Jia.

Original contents from the forked project maintain copyrights of the NewPipe team.
