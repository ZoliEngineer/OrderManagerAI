# Route 53 zone + one wildcard ACM certificate (covers app., api., advisor.).
# First apply: `terraform apply -target=aws_route53_zone.main`, delegate the domain to the printed name servers
# (automatic if the domain was registered in Route 53), then run a full apply — certificate validation waits for DNS.
resource "aws_route53_zone" "main" {
  name = var.domain_name
}

resource "aws_acm_certificate" "main" {
  domain_name               = var.domain_name
  subject_alternative_names = ["*.${var.domain_name}"]
  validation_method         = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "cert_validation" {
  for_each = {
    for o in aws_acm_certificate.main.domain_validation_options : o.domain_name => {
      name   = o.resource_record_name
      type   = o.resource_record_type
      record = o.resource_record_value
    }
  }
  zone_id         = aws_route53_zone.main.zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "main" {
  certificate_arn         = aws_acm_certificate.main.arn
  validation_record_fqdns = [for r in aws_route53_record.cert_validation : r.fqdn]
}

resource "aws_route53_record" "app" {
  zone_id = aws_route53_zone.main.zone_id
  name    = "app.${var.domain_name}"
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.frontend.domain_name
    zone_id                = aws_cloudfront_distribution.frontend.hosted_zone_id
    evaluate_target_health = false
  }
}

# api. and advisor. exist only while the ALB does
resource "aws_route53_record" "alb" {
  for_each = var.alb_enabled ? toset(["api", "advisor"]) : toset([])
  zone_id  = aws_route53_zone.main.zone_id
  name     = "${each.key}.${var.domain_name}"
  type     = "A"

  alias {
    name                   = aws_lb.main[0].dns_name
    zone_id                = aws_lb.main[0].zone_id
    evaluate_target_health = false
  }
}
