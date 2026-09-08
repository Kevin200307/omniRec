export interface DemoProduct {
  id: string;
  title: string;
  price: number;
}

export const DEMO_PRODUCTS: DemoProduct[] = [
  { id: "sku-1", title: "Wireless Headphones", price: 79.99 },
  { id: "sku-2", title: "Mechanical Keyboard", price: 129.0 },
  { id: "sku-3", title: "Standing Desk Mat", price: 39.5 },
  { id: "sku-4", title: "USB-C Hub", price: 24.99 },
  { id: "sku-5", title: "Ergonomic Mouse", price: 49.0 },
  { id: "sku-6", title: "4K Webcam", price: 89.99 },
];

export const DEMO_USER_ID = "demo-user";
