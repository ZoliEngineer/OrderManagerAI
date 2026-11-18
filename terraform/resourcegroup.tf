# Tag-based resource group: the AWS counterpart of the Azure resource group for this application.
# Every resource gets Project/Environment through the provider's default_tags; this group collects them in
# Resource Groups & Tag Editor (and can be used as a scope for Systems Manager, CloudWatch and cost views).
resource "aws_resourcegroups_group" "main" {
  name        = local.prefix
  description = "All resources of ${var.project_name} ${var.environment}"

  resource_query {
    query = jsonencode({
      ResourceTypeFilters = ["AWS::AllSupported"]
      TagFilters = [
        { Key = "Project", Values = [var.project_name] },
        { Key = "Environment", Values = [var.environment] },
      ]
    })
  }
}
