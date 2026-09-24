/* SPDX-License-Identifier: MIT */
"use client";

import { useActionState, useState } from "react";
import { useFormStatus } from "react-dom";

import { postTweetAction } from "@/app/actions";
import { NO_ERROR } from "@/lib/forms";

const LIMIT = 280;

function SubmitButton({ disabled }: { disabled: boolean }) {
  const { pending } = useFormStatus();
  return (
    <button
      type="submit"
      disabled={pending || disabled}
      className="rounded-full bg-sky-600 px-5 py-1.5 text-sm font-medium text-white hover:bg-sky-700 disabled:opacity-50"
    >
      {pending ? "Posting…" : "Post"}
    </button>
  );
}

/**
 * The compose box.
 *
 * A client component only because it needs the live character count and the pending state;
 * the post itself is a server action, so no token and no API address reach the browser.
 */
export function Composer() {
  const [state, action] = useActionState(postTweetAction, NO_ERROR);
  const [text, setText] = useState("");

  // Clear only on a confirmed success. Clearing optimistically would discard what the user
  // wrote when the post was rejected.
  //
  // This was a useEffect that called setText(""), which is the obvious way to write it and
  // the wrong one: an effect runs after the browser has already painted the committed state,
  // so the cleared box is a second render the user can in principle see. React's documented
  // way to reset state in response to a changed value is to do it during render, guarded by
  // the previous value held in state -- React discards the in-progress render and restarts
  // before committing anything, so there is no extra paint. The lint rule
  // react-hooks/set-state-in-effect, new in this eslint-config-next, is what surfaced it.
  const [clearedAt, setClearedAt] = useState(state.succeededAt);
  if (state.succeededAt !== clearedAt) {
    setClearedAt(state.succeededAt);
    if (state.succeededAt) {
      setText("");
    }
  }

  // Code points, so an emoji counts once — the same unit tweet-service validates in.
  const used = [...text].length;
  const over = used > LIMIT;

  return (
    <form
      action={action}
      className="border-b border-slate-200 p-4 dark:border-slate-800"
      data-testid="composer"
    >
      <textarea
        name="text"
        rows={3}
        value={text}
        onChange={(event) => setText(event.target.value)}
        placeholder="What's happening?"
        aria-label="Tweet text"
        className="w-full resize-none bg-transparent text-lg outline-none placeholder:text-slate-400"
      />
      {state.error ? (
        <p role="alert" className="mb-2 text-sm text-red-600">
          {state.error}
        </p>
      ) : null}
      <div className="flex items-center justify-end gap-3">
        <span
          data-testid="composer-count"
          className={over ? "text-sm font-medium text-red-600" : "text-sm text-slate-400"}
        >
          {LIMIT - used}
        </span>
        <SubmitButton disabled={over || used === 0} />
      </div>
    </form>
  );
}
