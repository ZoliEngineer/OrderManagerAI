// The chat page loads its libraries as <script> tags from the CDN (see index.html), which put them on
// `window` instead of being imported. These declarations tell the type checker those globals exist,
// borrowing the types from the npm packages pinned in package.json at the same versions.
// Keep the versions in package.json and index.html in step.

declare const oidc: typeof import('oidc-client-ts');
declare const marked: typeof import('marked');
declare const DOMPurify: typeof import('dompurify').default;
