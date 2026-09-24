/* SPDX-License-Identifier: MIT */
import type { Metadata } from "next";

import "./globals.css";

export const metadata: Metadata = {
  title: "chirp",
  description: "A microblogging product, built as the payload for an end-to-end delivery pipeline",
};

/**
 * No `next/font/google` here, deliberately.
 *
 * The scaffold's default fetches font files from Google at build time, which makes
 * `docker build` require outbound internet and fail in an air-gapped or
 * network-policy-restricted CI runner. A system font stack costs nothing and builds anywhere.
 */
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body className="antialiased">{children}</body>
    </html>
  );
}
