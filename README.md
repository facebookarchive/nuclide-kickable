# Kickables

Kickables (KX) are a state management abstraction targeted at very high scale, concurrent applications.

These abstractions are heavily influenced by RX for their APIs, but, unlike RX, are designed with state management goal in mind.

Kickables are lazy, provide powerful, dependency-aware caching and support efficient automatic invalidation.

They are heavily oriented at performance and simplicity of use.

This is a Java language implementation of this concept.

# Building

The (OSS version of the) project is built with Maven:
```
mvn package
```

# Contributing

See the CONTRIBUTING.md file for how to help out.

## License

nuclide-kickable is MIT-licensed.

# Logging/TODO

The Kickable library comes with its wrapper for `java.util.logging` classes. It logs on the global logger.

TODO: This should probably be split out.
