import React, { useState, useEffect } from 'react';

function App() {
  const [message, setMessage] = useState('Loading...');
  const [error, setError] = useState(null);

  useEffect(() => {
    const apiUrl = process.env.REACT_APP_API_URL || '';
    fetch(`${apiUrl}/api/hello`)
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.text();
      })
      .then((data) => setMessage(data))
      .catch((err) => setError(err.message));
  }, []);

  return (
    <div style={{ textAlign: 'center', marginTop: '100px', fontFamily: 'sans-serif' }}>
      <h1>{error ? `Error: ${error}` : message}</h1>
    </div>
  );
}

export default App;
