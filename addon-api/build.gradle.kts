// The small, stable contract that addon jars compile against. This is the module
// published to GitHub Packages.
//
// Addons must declare it `compileOnly` and NEVER shade it: the host loads addons
// with a classloader whose parent is the host's, so these classes must resolve to
// the host's class objects. A shaded copy is a different class with the same name,
// `isAssignableFrom` fails, and the addon won't load.
//
// Publishing + the Folia dependency are configured in the root build.
