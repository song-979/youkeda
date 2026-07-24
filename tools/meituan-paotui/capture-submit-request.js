'use strict';

const http = require('node:http');
const https = require('node:https');
const { PassThrough, Writable } = require('node:stream');

const SUBMIT_PATH = '/v1/api/paotui/submit_order';
const PREVIEW_PATH = '/v1/api/paotui/delivery_preview';
const CAPTURE_PREFIX = '[YOUKEDA_SUBMIT_CAPTURE] ';
const PREVIEW_PREFIX = '[YOUKEDA_PREVIEW_CAPTURE] ';

function optionUrl(options) {
  if (typeof options === 'string') {
    return options;
  }
  if (options instanceof URL) {
    return options.toString();
  }
  const protocol = options.protocol || '';
  const host = options.hostname || options.host || '';
  return `${protocol}//${host}${options.path || options.pathname || ''}`;
}

function redactValue(key, value) {
  const normalized = key.toLowerCase();
  if (normalized.includes('token')
      || normalized.includes('authorization')
      || normalized === 'sign'
      || normalized === 'signature'
      || normalized === 'mtcp-ak') {
    return '<redacted>';
  }
  if (normalized === 'phone' && typeof value === 'string' && value.length >= 7) {
    return `${value.slice(0, 3)}****${value.slice(-4)}`;
  }
  return value;
}

function sanitize(value) {
  if (Array.isArray(value)) {
    return value.map(sanitize);
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, child]) => [
      key,
      sanitize(redactValue(key, child)),
    ]));
  }
  return value;
}

function parseBody(rawBody) {
  if (!rawBody) {
    return '';
  }
  try {
    return sanitize(JSON.parse(rawBody));
  } catch {
    return rawBody;
  }
}

function sanitizeHeaders(headers) {
  return Object.fromEntries(Object.entries(headers || {}).map(([key, value]) => [
    key,
    redactValue(key, value),
  ]));
}

function capturedRequest(options, callback) {
  const chunks = [];
  const headers = { ...(options.headers || {}) };
  let responded = false;

  const request = new Writable({
    write(chunk, encoding, done) {
      chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk, encoding));
      done();
    },
    final(done) {
      const rawBody = Buffer.concat(chunks).toString('utf8');
      const capture = {
        method: options.method || 'POST',
        url: optionUrl(options),
        headers: sanitizeHeaders(headers),
        body: parseBody(rawBody),
        rawBodyLength: Buffer.byteLength(rawBody),
      };
      process.stderr.write(`${CAPTURE_PREFIX}${JSON.stringify(capture)}\n`);

      if (!responded) {
        responded = true;
        process.nextTick(() => {
          const response = new PassThrough();
          response.statusCode = 200;
          response.statusMessage = 'OK';
          response.headers = { 'content-type': 'application/json; charset=utf-8' };
          response.rawHeaders = ['content-type', 'application/json; charset=utf-8'];
          response.req = request;
          callback(response);
          response.end(JSON.stringify({
            status: 'error',
            message: 'YOUKEDA_CAPTURE_ONLY',
            code: 19998,
          }));
        });
      }
      done();
    },
  });

  request.method = options.method || 'POST';
  request.path = options.path || options.pathname || SUBMIT_PATH;
  request.setHeader = (name, value) => { headers[name] = value; };
  request.getHeader = (name) => headers[name];
  request.getHeaders = () => ({ ...headers });
  request.getHeaderNames = () => Object.keys(headers);
  request.hasHeader = (name) => Object.hasOwn(headers, name);
  request.removeHeader = (name) => { delete headers[name]; };
  request.flushHeaders = () => {};
  request.setTimeout = (_timeout, listener) => {
    if (listener) {
      request.once('timeout', listener);
    }
    return request;
  };
  request.setNoDelay = () => request;
  request.setSocketKeepAlive = () => request;
  request.abort = () => request.destroy();
  return request;
}

function observeRequest(request, options) {
  const chunks = [];
  let logged = false;
  const originalWrite = request.write;
  const originalEnd = request.end;

  request.write = function write(chunk, encoding) {
    if (chunk) {
      chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk, encoding));
    }
    return originalWrite.apply(this, arguments);
  };
  request.end = function end(chunk, encoding) {
    if (chunk) {
      chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk, encoding));
    }
    if (!logged) {
      logged = true;
      const rawBody = Buffer.concat(chunks).toString('utf8');
      const capture = {
        method: options.method || 'POST',
        url: optionUrl(options),
        headers: sanitizeHeaders(options.headers || {}),
        body: parseBody(rawBody),
        rawBodyLength: Buffer.byteLength(rawBody),
      };
      process.stderr.write(`${PREVIEW_PREFIX}${JSON.stringify(capture)}\n`);
    }
    return originalEnd.apply(this, arguments);
  };
  return request;
}

function patchTransport(transport) {
  const originalRequest = transport.request;
  transport.request = function request(options, callback) {
    const url = optionUrl(options);
    if (url.includes(SUBMIT_PATH)) {
      return capturedRequest(options, callback);
    }
    const request = originalRequest.apply(this, arguments);
    return url.includes(PREVIEW_PATH) ? observeRequest(request, options) : request;
  };
}

patchTransport(http);
patchTransport(https);
