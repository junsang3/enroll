import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

export const options = {
  vus: 100,
  duration: '30s',
};

const created = new Counter('created');
const conflict = new Counter('conflict');
const unexpected = new Counter('unexpected');

export default function () {
  // const courseId = Math.floor(Math.random() * 500) + 1;
  // const studentId = Math.floor(Math.random() * 10000) + 1;
  const studentId = (__ITER * 100 + __VU) % 10000 + 1;
  const courseId = 1;

  const res = http.post(
    `http://172.30.1.73:8080/enrollments`,
    JSON.stringify({ studentId, courseId }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  if (res.status === 201) created.add(1);
  else if (res.status === 409) conflict.add(1);
  else unexpected.add(1);

  check(res, {
    '201 or 409': (r) => r.status === 201 || r.status === 409,
  });
}
