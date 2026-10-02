# @omnirec/commerce-node

```ts
import { createOmnirecServer, identityFromCookies } from "@omnirec/commerce-node";

const omnirec = createOmnirecServer({ endpoint: "https://events.example.com" });
omnirec.track("purchase_completed", { order }, {
  identity: { ...identityFromCookies(req.headers.cookie), userId: user.id },
  eventId: `evt:purchase_completed:${order.id}`,
});
await omnirec.flush(); // before a serverless function returns
```
