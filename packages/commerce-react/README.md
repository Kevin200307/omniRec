# @omnirec/commerce-react

```tsx
import { OmnirecProvider, useTrack, Track } from "@omnirec/commerce-react";

<OmnirecProvider endpoint="/omnirec"><App /></OmnirecProvider>;

const track = useTrack();
track("product_added_to_cart", { product: { id, quantity: 1 } });
<Track event="product_clicked" data={{ product: { id } }}><a href={url}>{name}</a></Track>;
```

Also `useImpression(ref, event, data)`. Safe under Strict Mode and server rendering.
