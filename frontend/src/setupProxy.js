const { createProxyMiddleware } = require('http-proxy-middleware');

const MARKET_DATA  = 'http://localhost:8080';
const ACCOUNT_SVC  = 'http://localhost:8081';

module.exports = function (app) {
  app.use('/api/accounts', createProxyMiddleware({ target: ACCOUNT_SVC, changeOrigin: true }));
  app.use('/api',          createProxyMiddleware({ target: MARKET_DATA,  changeOrigin: true }));
  app.use('/ws',           createProxyMiddleware({ target: MARKET_DATA,  ws: true, changeOrigin: true }));
};
