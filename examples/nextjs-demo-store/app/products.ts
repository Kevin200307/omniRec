// SPDX-License-Identifier: Apache-2.0
export interface DemoProduct {
  id: string;
  title: string;
  price: number;
  categoryId: string;
}

export const DEMO_PRODUCTS: DemoProduct[] = [
  { id: "p1", title: "Wireless Headphones", price: 79.99, categoryId: "audio" },
  { id: "p2", title: "Mechanical Keyboard", price: 129.0, categoryId: "peripherals" },
  { id: "p3", title: "Standing Desk Mat", price: 39.5, categoryId: "office" },
  { id: "p4", title: "USB-C Hub", price: 24.99, categoryId: "peripherals" },
  { id: "p5", title: "Ergonomic Mouse", price: 49.0, categoryId: "peripherals" },
  { id: "p6", title: "4K Webcam", price: 89.99, categoryId: "video" },
];

export const DEMO_USER_ID = "customer_123";
