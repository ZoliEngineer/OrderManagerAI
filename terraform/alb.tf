# Internal-facing ALB behind CloudFront. Idle timeout 900 s; CloudFront itself caps origin silence at 60 s, so
# the advisor sends SSE keep-alive comments every 15 s.
resource "random_password" "origin_verify" {
  length  = 32
  special = false
}

resource "aws_lb" "main" {
  count              = var.alb_enabled ? 1 : 0
  name               = local.prefix
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id
  idle_timeout       = 900
}

# Plain HTTP: CloudFront terminates TLS. Requests without the secret header (anything not coming through
# CloudFront) get the default 403.
resource "aws_lb_listener" "http" {
  count             = var.alb_enabled ? 1 : 0
  load_balancer_arn = aws_lb.main[0].arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }
}
