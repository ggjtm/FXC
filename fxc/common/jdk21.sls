{#- JDK 21: FxcExchange, FxcBroker, FxcPub, FxcInvestor (all four Java components + their embedded
    GridGain nodes). Matches root README.md's "JDK requirements" table. #}
{% from 'fxc/map.jinja' import fxc with context %}

fxc-jdk21:
  pkg.installed:
    - name: {{ fxc.jdk21_pkg }}
