# Grafana with provisioning and dashboards baked in, for Azure (locally compose mounts them instead).
FROM grafana/grafana:12.1.0
COPY provisioning /etc/grafana/provisioning
COPY dashboards /etc/grafana/dashboards
# The Tempo data source is local-only (Azure has no Tempo yet); it would fail on the missing TEMPO_URL.
RUN rm /etc/grafana/provisioning/datasources/tempo.yml
