import instance from './instance'

export async function fetchVapidPublicKey() {
  const { data } = await instance.get('/api/v1/notifications/vapid-public-key', {
    skipAuthRedirect: true,
  })
  return data?.data?.publicKey || ''
}

export async function subscribePush({ endpoint, p256dh, auth }) {
  return instance.post('/api/v1/notifications/subscribe', { endpoint, p256dh, auth })
}

export async function unsubscribePush({ endpoint }) {
  return instance.post('/api/v1/notifications/unsubscribe', { endpoint })
}