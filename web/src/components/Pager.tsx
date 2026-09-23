/* SPDX-License-Identifier: MIT */
import Link from "next/link";

/**
 * Next-page link.
 *
 * Cursor-only, with no page numbers and no "previous". Every list in this system is keyset
 * paged over an opaque cursor, which has no notion of an ordinal page and cannot be walked
 * backwards; rendering numbered pages would require an offset the services deliberately do
 * not support.
 */
export function Pager({
  basePath,
  params,
  nextCursor,
}: {
  basePath: string;
  params?: Record<string, string>;
  nextCursor: string | null;
}) {
  if (!nextCursor) {
    return null;
  }
  const search = new URLSearchParams({ ...params, cursor: nextCursor });
  return (
    <div className="p-4 text-center">
      <Link
        href={`${basePath}?${search.toString()}`}
        data-testid="next-page"
        className="text-sm font-medium text-sky-600 hover:underline"
      >
        Show more
      </Link>
    </div>
  );
}
