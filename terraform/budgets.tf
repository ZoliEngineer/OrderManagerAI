resource "aws_budgets_budget" "monthly" {
  name         = "${local.prefix}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.budget_limit_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # Account-wide on purpose. Cost allocation tags are not available on this account type, so a filter on the
  # `Project` tag would match nothing and the budget would stay at $0 and never alert. Spend of any other app in
  # the account therefore counts too: set budget_limit_usd to this project's share plus the other apps' usage.
  #
  # Credits are excluded from the amount, so this tracks the real (pre-credit) cost: it still alerts when free
  # credits would hide a forgotten, running environment.
  cost_types {
    include_credit = false
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.budget_email]
  }
}
