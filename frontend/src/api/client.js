const BASE_URL = process.env.REACT_APP_API_URL ?? '';

async function request(path) {
  const response = await fetch(`${BASE_URL}${path}`);
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

export default request;
