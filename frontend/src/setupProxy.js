const { createProxyMiddleware } = require('http-proxy-middleware');

const BACKEND = 'http://localhost:8080';

module.exports = function (app) {
  app.use('/api', createProxyMiddleware({ target: BACKEND, changeOrigin: true }));
  app.use('/ws',  createProxyMiddleware({ target: BACKEND, ws: true, changeOrigin: true }));
};
