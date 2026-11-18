# Prometheus with the AWS scrape config and the alert rules baked in, for ECS (locally compose mounts them instead).
# Build context: observability/prometheus
FROM prom/prometheus:v3.5.0
COPY prometheus.aws.yml /etc/prometheus/prometheus.yml
COPY rules /etc/prometheus/rules
# Data is on ephemeral task storage and is lost on restart; 2 days is plenty for "is it up, and why not".
CMD ["--config.file=/etc/prometheus/prometheus.yml", "--storage.tsdb.path=/prometheus", "--storage.tsdb.retention.time=2d"]
