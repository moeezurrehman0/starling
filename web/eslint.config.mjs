// eslint-config-next 16 ships flat config natively: each entry point is already an array of
// config objects. Until 15 it shipped eslintrc-style objects, which had to be dragged into
// flat config through `FlatCompat` from @eslint/eslintrc. That shim now fails outright --
// it JSON.stringify's the config to validate it, and the native config holds a cycle through
// the react plugin -- so the compat layer is gone rather than worked around.
//
// The two presets are the same ones the old config named as "next/core-web-vitals" and
// "next/typescript"; importing them by subpath keeps the rule set identical.
import coreWebVitals from "eslint-config-next/core-web-vitals";
import typescript from "eslint-config-next/typescript";

const eslintConfig = [
  ...coreWebVitals,
  ...typescript,
  {
    ignores: [
      "node_modules/**",
      ".next/**",
      "out/**",
      "build/**",
      "next-env.d.ts",
    ],
  },
];

export default eslintConfig;
