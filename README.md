# jar-relocator

This is a fork of [jar-relocator by lucko](https://github.com/lucko/jar-relocator/) with some changes required for our own use.

#### Full list of changes

* Packages renamed from `me.lucko` to `com.hypherionmc`.
* Added a DirectoryRelocator task that allows remapping files inside a directory, instead of a jar
* Added a runDirectory() method to JarRelocator.
* Converted from MAVEN to Gradle
* Not published to maven central
* Not signed

This fork will probably not be of much use to anyone else, but it's kept here for source control, and to disclose the source of the library

### License

This library is licensed under Apache License Version 2.0, just like the original.