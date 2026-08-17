{#- Shared base for every fxc.<component>: service account + base directory. No JDK here — each
    component includes fxc.common.jdk21 or fxc.common.jdk17 for the version it actually needs
    (Tigase is JDK 17, everything else is JDK 21; see fxc/docs/PROBLEMS.md). #}
{% from 'fxc/map.jinja' import fxc with context %}

fxc-service-group:
  group.present:
    - name: {{ fxc.service_group }}
    - system: true

fxc-service-user:
  user.present:
    - name: {{ fxc.service_user }}
    - gid: {{ fxc.service_group }}
    - system: true
    - home: {{ fxc.base_dir }}
    - createhome: false
    - shell: /usr/sbin/nologin
    - require:
      - group: fxc-service-group

fxc-base-dir:
  file.directory:
    - name: {{ fxc.base_dir }}
    - user: {{ fxc.service_user }}
    - group: {{ fxc.service_group }}
    - mode: '0755'
    - makedirs: true
    - require:
      - user: fxc-service-user
