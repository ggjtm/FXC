{#- JDK 17: Tigase only. Tigase 8.4.1 bundles a Groovy whose ASM cannot read Java 21+ class files
    ("Unsupported class file major version 65") — see docker/tigase/Dockerfile and root
    README.md's "JDK requirements" table. Safe to coexist with jdk21.sls only because the default
    topology gives Tigase its own EC2 instance (fxc/docs/PROBLEMS.md flags the all-in-one risk). #}
{% from 'fxc/map.jinja' import fxc with context %}

{% if fxc.get('jdk17_from_adoptium') %}
{#- Debian 13+ has no openjdk-17 in the archive (fxc/docs/PROBLEMS.md P9): pull Temurin 17 from
    Adoptium, which publishes for every current Debian/Ubuntu codename and arch. #}
fxc-jdk17-adoptium-repo:
  pkgrepo.managed:
    - name: deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb {{ grains['oscodename'] }} main
    - file: /etc/apt/sources.list.d/adoptium.list
    - key_url: https://packages.adoptium.net/artifactory/api/gpg/key/public
    - aptkey: false
    - require_in:
      - pkg: fxc-jdk17
{% endif %}

fxc-jdk17:
  pkg.installed:
    - name: {{ fxc.jdk17_pkg }}
