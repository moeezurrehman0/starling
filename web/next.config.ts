import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  /**
   * Emits `.next/standalone`: a self-contained server plus only the `node_modules` actually
   * reached. It is what lets the runtime image copy three directories instead of installing
   * dependencies, and is the difference between a ~200 MB image and a ~1 GB one.
   */
  output: "standalone",

  // The build must fail on a type error, not warn. This is the frontend's equivalent of the
  // JVM side's -Werror.
  //
  // There was an `eslint: { ignoreDuringBuilds: false }` here too. Next 16 removed ESLint
  // from the build and the key from NextConfig, so keeping it is a type error. The gate did
  // not go away with it: the web job runs `npm run lint` as its own step, which is where
  // lint belonged anyway -- a lint failure and a build failure are different questions and
  // reading them off one exit code was never worth the convenience.
  typescript: { ignoreBuildErrors: false },

  // The version banner is a free disclosure of which Next release to look up CVEs for.
  poweredByHeader: false,

  // Every response is user-specific and rendered per request; a stale ETag would only ever
  // serve one account's page to another.
  generateEtags: false,
};

export default nextConfig;
