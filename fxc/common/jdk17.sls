{#- JDK 17: Tigase only. Tigase 8.4.1 bundles a Groovy whose ASM cannot read Java 21+ class files
    ("Unsupported class file major version 65") — see docker/tigase/Dockerfile and root
    README.md's "JDK requirements" table. Safe to coexist with jdk21.sls only because the default
    topology gives Tigase its own EC2 instance (fxc/docs/PROBLEMS.md flags the all-in-one risk). #}
{% from 'fxc/map.jinja' import fxc with context %}

fxc-jdk17:
  pkg.installed:
    - name: {{ fxc.jdk17_pkg }}
