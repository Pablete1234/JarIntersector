# JarIntersector

This is a small java program which takes in two jars, and will output the intersection of both.
The purpose of this is being able to create a minimum denomination/specification that both jars contain/meet.
Note that this is only really useful for different versions of libraries/apis that aim to keep some level of backwards compat.
It is expected that you, on top of this, will have some amount of classes that are built depending on the specific versions jars and dynamically pick the right one at runtime.

### Important details
 - The jar you get as output is NOT something you're supposed to be able to run; methods may reference stuff that was stripped and create all kinds of errors! This is ONLY to be used as provided dependency, that at runtime will be one of the original jars.
 - Due to the simplistic logic that drives this, input order of the jars matters. The first jar is the base or older version, that is matched against the 2nd jar or newer version, anything that still exists in the newer version stays, anything not found in newer is stripped.
 - It is assumed that, as a general tendency, software tends to drive towards more abstraction layers over time. This is relevant because extra added layers of interfaces are handled well (the intermediates will be stripped but originals kept), however if the version merged multiple interfaces into one you won't see them in the result.

### Why does this exist?
This was built to merge minecraft (bukkit) apis, and have a common base dependency of spigot-api that will work all the way from 1.8.8 to 1.20.6. Additional platform-modules can hold the logic that is specific to different versions.
Others depend on 1.8.8 as the lowest denominator, but this can silently break things if for example an enum constant is renamed, in this jar instead you'll get a compile error as the enum entry is stripped; then it is your job to create either a version-independent implementation, or have two different implementations and call the right one dynamically.

## Usage
`java JarIntersector <inputJar1> <inputJar2> <outputJar> [logging]`

`logging` defaults to `MODIFIED`, and can be set to one of:
 - `NONE` no logging is performed
 - `MODIFIED` log all removed/modified methods/fields
 - `FULL` also produce a full jar report at the end (with all methods in all classes), useful if you want to check/search things
 
These logs can help you identify what was removed


## Transformations done
 - Interfaces are replaced by the common set of interfaces shared by both trees
   - Keep all interfaces in jar 1 as long as jar 2 implements them directly or indirectly
   - For interfaces not implemented, attempt to replace them by the set of interfaces that extends, and repeat
 - Methods are replaced by only the ones present in both jar 1 and jar 2 
   - Keep the methods existing in jar 1, as long as jar 2 has them somewhere in the tree
 - Fields are replaced by only the ones present in bot jar 1 and jar 2
   - Keep the fields existing in jar 1, as long as jar 2 has them somewhere in the tree
   - This also applies to enum constants, as they're essentially public static final fields with an extra enum modifier
