// SPDX-License-Identifier: Apache-2.0
/** @type {import('next').NextConfig} */
const nextConfig = {
  // Packages published from the monorepo's own workspace — transpile
  // rather than expect a pre-published npm build during local dev.
  transpilePackages: ["@omnirec/core", "@omnirec/react", "@omnirec/react-ui"],
};

export default nextConfig;
