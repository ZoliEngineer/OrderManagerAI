# Grafana with provisioning and dashboards baked in, for Azure (locally compose mounts them instead).
FROM grafana/grafana:12.1.0
COPY provisioning /etc/grafana/provisioning
COPY dashboards /etc/grafana/dashboards
# provisioning/datasources/tempo.yml is excluded by .dockerignore: it is local-only (Azure has no Tempo yet)
# and would fail Grafana's startup on the missing TEMPO_URL.
