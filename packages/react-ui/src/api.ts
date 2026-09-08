/**
 * Thin fetch wrapper shared by the react-ui components. This is the only
 * place these components know a backend exists — none of them import a
 * provider SDK or know whether Algolia/Personalize/Google RecAI is behind
 * the response.
 */
export async function omnirecFetch<T>(endpoint: string, path: string, params: Record<string, string>): Promise<T> {
  const url = new URL(`${endpoint.replace(/\/$/, "")}${path}`);
  for (const [key, value] of Object.entries(params)) url.searchParams.set(key, value);
  const res = await fetch(url.toString());
  if (!res.ok) {
    throw new Error(`Omnirec request to ${path} failed: ${res.status}`);
  }
  return res.json() as Promise<T>;
}
