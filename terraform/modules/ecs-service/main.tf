# One Fargate service: task definition, ECS service, optional Cloud Map DNS name and ALB routing.

locals {
  has_port = var.container_port != null
  # Public IP + security groups instead of NAT: tasks in public subnets, inbound only from the ALB / peers.
  health_port = coalesce(var.health_port, var.container_port, 0)
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${var.name}"
  retention_in_days = var.log_retention_days
}

resource "aws_ecs_task_definition" "this" {
  family                   = var.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([
    {
      name      = var.name
      image     = var.image
      essential = true

      portMappings = local.has_port ? [{ containerPort = var.container_port, protocol = "tcp" }] : []

      environment = [for k, v in var.environment : { name = k, value = v }]
      secrets     = [for k, v in var.secrets : { name = k, valueFrom = v }]

      # busybox wget ships with the alpine JRE image; Spring Boot's JVM needs a long start period on 0.25 vCPU.
      healthCheck = local.health_port > 0 ? {
        command     = ["CMD-SHELL", "wget -qO- http://localhost:${local.health_port}${var.health_path} || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 120
      } : null

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.this.name
          awslogs-region        = var.region
          awslogs-stream-prefix = "ecs"
        }
      }
    }
  ])
}

# ── Cloud Map: <discovery_name>.<namespace> resolves to the task IPs ─────────────
resource "aws_service_discovery_service" "this" {
  count = var.discovery_name != null ? 1 : 0
  name  = var.discovery_name

  dns_config {
    namespace_id   = var.discovery_namespace_id
    routing_policy = "MULTIVALUE"
    dns_records {
      type = "A"
      ttl  = 10
    }
  }
}

# ── ALB routing (target group is free; the listener rules exist only while the ALB does) ──
resource "aws_lb_target_group" "this" {
  count       = local.has_port && length(var.alb_rules) > 0 ? 1 : 0
  name        = substr(var.name, 0, 32)
  port        = var.container_port
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id

  deregistration_delay = 15

  health_check {
    path                = var.health_path
    matcher             = "200"
    interval            = 30
    healthy_threshold   = 2
    unhealthy_threshold = 5
  }
}

resource "aws_lb_listener_rule" "this" {
  for_each     = var.alb_enabled && length(aws_lb_target_group.this) > 0 ? { for i, r in var.alb_rules : tostring(i) => r } : {}
  listener_arn = var.listener_arn
  priority     = each.value.priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this[0].arn
  }

  dynamic "condition" {
    for_each = var.origin_verify != null ? [1] : []
    content {
      http_header {
        http_header_name = "X-Origin-Verify"
        values           = [var.origin_verify]
      }
    }
  }

  dynamic "condition" {
    for_each = length(each.value.hosts) > 0 ? [1] : []
    content {
      host_header {
        values = each.value.hosts
      }
    }
  }

  dynamic "condition" {
    for_each = length(each.value.paths) > 0 ? [1] : []
    content {
      path_pattern {
        values = each.value.paths
      }
    }
  }
}

resource "aws_ecs_service" "this" {
  name            = var.name
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count

  # Replace the single task in place: stop first, then start (no double Kafka consumer, no extra cost).
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  deployment_circuit_breaker {
    enable   = true
    rollback = false
  }

  dynamic "capacity_provider_strategy" {
    for_each = var.use_spot ? [1] : []
    content {
      capacity_provider = "FARGATE_SPOT"
      weight            = 1
    }
  }

  launch_type = var.use_spot ? null : "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = var.security_group_ids
    assign_public_ip = true
  }

  dynamic "service_registries" {
    for_each = aws_service_discovery_service.this
    content {
      registry_arn = service_registries.value.arn
    }
  }

  dynamic "load_balancer" {
    for_each = var.alb_enabled && length(aws_lb_target_group.this) > 0 ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.this[0].arn
      container_name   = var.name
      container_port   = var.container_port
    }
  }

  health_check_grace_period_seconds = var.alb_enabled && length(aws_lb_target_group.this) > 0 ? 180 : null

  # env-up / env-down scale services outside Terraform. Deploys push :latest and force a new deployment,
  # so the task definition (env vars, secrets, sizing) stays Terraform-managed.
  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [aws_lb_listener_rule.this]
}
