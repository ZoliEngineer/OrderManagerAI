# Azure variant of prometheus.yml. Rendered by Terraform (terraform/main.tf, module "prometheus"):
# The prefix and domain placeholders are Terraform template variables, not Prometheus syntax.
# Same job names and labels as the local file, so the Grafana dashboards work unchanged.
global:
  scrape_interval: 30s      # slower than local: fewer samples, quieter logs
  evaluation_interval: 30s

# Same rules file as local. Delivered as a Container App secret; file name = secret name.
rule_files:
  - /etc/prometheus-config/prometheus-rules

scrape_configs:
%{ for job, svc in { marketdata = "marketdata", accountservice = "acct-svc", orderservice = "order-svc", mcpservice = "mcp-svc", advisorservice = "advisor-svc" } ~}
  - job_name: ${job}
    metrics_path: /actuator/prometheus
    scheme: https
    static_configs:
      - targets: ["${prefix}-${svc}.${domain}:443"]
        labels:
          service: ${replace(job, "service", "")}
    metric_relabel_configs:
      - source_labels: [uri]
        regex: /actuator.*
        action: drop
%{ endfor ~}
  # Internal-only ingress: *.internal.<domain>. TLS verification is skipped because traffic never leaves the environment.
  - job_name: executionservice
    metrics_path: /actuator/prometheus
    scheme: https
    tls_config:
      insecure_skip_verify: true
    static_configs:
      - targets: ["${prefix}-exec-svc.internal.${domain}:443"]
        labels:
          service: execution
