# @omnirec/commerce-next

```tsx
// app/layout.tsx
import { OmnirecNextProvider } from "@omnirec/commerce-next";
<OmnirecNextProvider endpoint="/omnirec">{children}</OmnirecNextProvider>;

// app/api/orders/route.ts
import { omnirecServer, currentIdentity } from "@omnirec/commerce-next/server";
omnirecServer().track("purchase_completed", { order }, { identity: currentIdentity(user.id) });
```

Route changes send `page_viewed`; HTML attributes, impressions and autocapture are on by
default. Proxy the collector under your own origin with a rewrite in `next.config.mjs`:
`{ source: "/omnirec/:path*", destination: "https://events.example.com/:path*" }`.
