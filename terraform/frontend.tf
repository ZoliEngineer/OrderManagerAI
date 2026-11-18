# React SPA bucket (served through the CloudFront distribution in cloudfront.tf).
resource "aws_s3_bucket" "frontend" {
  bucket        = "${local.prefix}-frontend-${data.aws_caller_identity.current.account_id}"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket                  = aws_s3_bucket.frontend.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

data "aws_iam_policy_document" "frontend_bucket" {
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.frontend.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.main.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  policy = data.aws_iam_policy_document.frontend_bucket.json
}

# Runtime configuration read by src/config.js. The deploy job syncs the build with --exclude config.json.
resource "aws_s3_object" "config" {
  bucket        = aws_s3_bucket.frontend.id
  key           = "config.json"
  content_type  = "application/json"
  cache_control = "no-store"
  content = jsonencode({
    # Same origin for SPA, APIs and WebSocket: relative URLs, no CORS
    apiUrl         = ""
    accountsApiUrl = ""
    ordersApiUrl   = ""
    wsBase         = ""
    oidc = {
      authority = local.oidc_issuer
      clientId  = aws_cognito_user_pool_client.web.id
      scope     = "${aws_cognito_resource_server.api.identifier}/market.read"
      logoutUrl = "https://${aws_cognito_user_pool_domain.main.domain}.auth.${var.region}.amazoncognito.com/logout?client_id=${aws_cognito_user_pool_client.web.id}&logout_uri=${local.app_origin}/"
    }
  })
}
