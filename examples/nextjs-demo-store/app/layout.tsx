// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import { Providers } from "./providers";

export const metadata = {
  title: "Omnirec Demo Store",
  description: "Proof-of-DX example app for the Omnirec framework.",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body style={{ fontFamily: "system-ui, sans-serif", margin: 0 }}>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
