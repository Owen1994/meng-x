import { useState } from 'react';
import { api, saveToken, saveCredentials, loadCredentials } from '../api';

const remembered = loadCredentials();

export default function Login({ onLogin }) {
  const [phone, setPhone] = useState(remembered?.phone || '');
  const [password, setPassword] = useState(remembered?.password || '');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e) {
    e.preventDefault();
    setError('');
    setSubmitting(true);
    try {
      const data = await api('/api/auth/login', {
        method: 'POST',
        body: { phone, password },
      });
      saveToken(data.token);
      saveCredentials(phone, password);
      onLogin({ userId: data.userId, nickname: data.nickname });
    } catch (err) {
      setError(err.message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="page">
      <div className="moon" />
      <h1 className="title">点子铺</h1>
      <p className="slogan">聊着聊着，好点子就长成真功能</p>
      <form className="login-card" onSubmit={handleSubmit}>
        <input
          type="tel"
          placeholder="手机号"
          maxLength={11}
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
          required
        />
        <input
          type="password"
          placeholder="密码"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
        {error && <p className="login-error">{error}</p>}
        <button type="submit" disabled={submitting}>
          {submitting ? '登录中…' : '进入点子铺'}
        </button>
        <p className="login-hint">没有账号？输入手机号和密码会自动创建</p>
      </form>
    </div>
  );
}
