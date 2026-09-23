import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  /**
   * Emits `.next/standalone`: a self-contained server plus only the `node_modules` actually
   * reached. It is what lets the runtime image copy three directories instead of installing
   * dependencies, and is the difference between a ~200 MB image and a ~1 GB one.
   */
  output: "standalone",

  // The build must fail on a type error or a lint error, not warn. This is the frontend's
  // equivalent of the JVM side's -Werror.
  typescript: { ignoreBuildErrors: false },
  eslint: { ignoreDuringBuilds: false },

  // The version banner is a free disclosure of which Next release to look up CVEs for.
  poweredByHeader: false,

  // Every response is user-specific and rendered per request; a stale ETag would only ever
  // serve one account's page to another.
  generateEtags: false,
};

export default nextConfig;
