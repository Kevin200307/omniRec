# @omnirec/commerce-vue

```ts
import { OmnirecPlugin } from "@omnirec/commerce-vue";
app.use(OmnirecPlugin, { endpoint: "/omnirec" });
```

```html
<button v-track="['product_added_to_cart', { product: { id, quantity: 1 } }]">Add</button>
<form v-track:submit="['search_performed', { search: { query } }]">...</form>
<section v-impression="['product_list_viewed', { list: { id: 'home', productIds } }]">...</section>
```

`useOmnirec()` returns the client in `setup()`; it is `null` during server rendering.
