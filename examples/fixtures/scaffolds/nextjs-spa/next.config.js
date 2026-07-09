/** @type {import('next').NextConfig} */
const nextConfig = {
  // Client-only SPA: static export, served from S3 + CloudFront.
  output: 'export',
};

module.exports = nextConfig;
